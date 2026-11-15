package com.example.omp.notification.durable;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.AbstractNotificationTest;
import com.example.omp.shared.events.KafkaTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DurableEventPipelineIT extends AbstractNotificationTest {
    @Autowired DomainEventIngestion ingestion;
    @Autowired EventQuarantine quarantine;
    @Autowired EventRecoveryService recovery;
    @Autowired DispatchQueue queue;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String, String> kafka;

    private String payload(UUID id) {
        return """
        {"eventId":"%s","eventType":"ORDER_CREATED","schemaVersion":1,"occurredAt":"%s","orderId":"00000000-0000-0000-0000-000000000301","customerId":"00000000-0000-0000-0000-000000000101","status":"DRAFT"}
        """
                .formatted(id, Instant.now());
    }

    @Test
    void duplicateKafkaDeliveryProducesOneDurableHandoff() {
        UUID id = UUID.randomUUID();
        String p = payload(id);
        ingestion.accept("live", p, KafkaTopics.ORDERS_EVENTS_ORDER);
        ingestion.accept("live", p, KafkaTopics.ORDERS_EVENTS_ORDER);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notification_dispatch WHERE payload LIKE ?",
                                Long.class,
                                "%" + id + "%"))
                .isEqualTo(1L);
    }

    @Test
    void duplicateIdWithChangedPayloadCannotProduceAnotherEffect() {
        UUID id = UUID.randomUUID();
        String p = payload(id);
        ingestion.accept("live", p, KafkaTopics.ORDERS_EVENTS_ORDER);
        assertThatThrownBy(
                        () ->
                                ingestion.accept(
                                        "live",
                                        p.replace("DRAFT", "PLACED"),
                                        KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM domain_event_inbox WHERE consumer_id='live' AND event_id=?",
                                Long.class,
                                id))
                .isEqualTo(1L);
    }

    @Test
    void databaseRollbackRemovesInboxAndDispatchTogether() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(
                                                s -> {
                                                    ingestion.accept(
                                                            "live",
                                                            payload(id),
                                                            KafkaTopics.ORDERS_EVENTS_ORDER);
                                                    throw new IllegalStateException(
                                                            "injected before commit");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM domain_event_inbox WHERE event_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notification_dispatch WHERE payload LIKE ?",
                                Long.class,
                                "%" + id + "%"))
                .isZero();
    }

    @Test
    void poisonRecordIsQuarantinedAndFollowingValidRecordAdvances() throws Exception {
        String secret = "poison-" + UUID.randomUUID();
        UUID valid = UUID.randomUUID();
        kafka.send(KafkaTopics.ORDERS_EVENTS_ORDER, 0, "same-partition", secret).get();
        kafka.send(KafkaTopics.ORDERS_EVENTS_ORDER, 0, "same-partition", payload(valid)).get();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM domain_event_quarantine WHERE payload=?",
                                                    Long.class,
                                                    secret))
                                    .isGreaterThanOrEqualTo(1L);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM domain_event_inbox WHERE consumer_id='live' AND event_id=?",
                                                    Long.class,
                                                    valid))
                                    .isEqualTo(1L);
                        });
        assertThat(recovery.quarantine(200).toString()).doesNotContain(secret);
    }

    @Test
    void concurrentReplayIsFencedAndAuditedOnce() throws Exception {
        long offset = Math.abs(UUID.randomUUID().getMostSignificantBits());
        quarantine.capture(
                "live",
                new ConsumerRecord<>(KafkaTopics.ORDERS_EVENTS_ORDER, 0, offset, "key", "invalid"),
                new IllegalArgumentException());
        UUID id =
                jdbc.queryForObject(
                        "SELECT id FROM domain_event_quarantine WHERE consumer_id='live' AND source_offset=?",
                        UUID.class,
                        offset);
        String corrected = payload(UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> replay(id, corrected));
            var second = executor.submit(() -> replay(id, corrected));
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_recovery_audit WHERE resource_id=?",
                                Long.class,
                                id))
                .isEqualTo(1L);
        assertThatThrownBy(() -> recovery.replay(id, "operator", "repeat", corrected))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private boolean replay(UUID id, String p) {
        try {
            recovery.replay(id, "operator", "verified corrected contract", p);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException conflict) {
            return false;
        }
    }

    @Test
    void invalidReplayRollsBackAndRemainsRecoverable() {
        long offset = Math.abs(UUID.randomUUID().getMostSignificantBits());
        quarantine.capture(
                "live",
                new ConsumerRecord<>(KafkaTopics.ORDERS_EVENTS_ORDER, 0, offset, "key", "invalid"),
                new IllegalArgumentException());
        UUID id =
                jdbc.queryForObject(
                        "SELECT id FROM domain_event_quarantine WHERE source_offset=?",
                        UUID.class,
                        offset);
        assertThatThrownBy(() -> recovery.replay(id, "operator", "still invalid", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT replayed_at FROM domain_event_quarantine WHERE id=?",
                                Object.class,
                                id))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_recovery_audit WHERE resource_id=?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void failedDispatchRetryIsAuditedAndCannotResetAnActiveDispatch() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_dispatch(id,transport,destination,message_key,payload,state) VALUES (?,'REDIS','notifications:orders','key','{}','FAILED')",
                id);
        recovery.retry(id, "operator", "broker repaired");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM notification_dispatch WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("PENDING");
        assertThatThrownBy(() -> recovery.retry(id, "operator", "concurrent retry"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_recovery_audit WHERE resource_id=?",
                                Long.class,
                                id))
                .isEqualTo(1L);
    }

    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test
    void recoveryOperationsRequireAnAdministratorAndBoundListRequests() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var uri =
                java.net.URI.create(
                        "http://localhost:" + port + "/api/v1/event-recovery/quarantine");
        assertThat(
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .isEqualTo(401);
        for (var request :
                java.util.List.of(
                        new String[] {"test-token-alice", "", "403"},
                        new String[] {"test-token-admin", "", "200"},
                        new String[] {"test-token-admin", "?limit=0", "400"})) {
            var response =
                    client.send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(uri + request[1]))
                                    .header("Authorization", "Bearer " + request[0])
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(Integer.parseInt(request[2]));
        }
    }

    @Test
    void quarantineStorageFailureDoesNotCommitAndLoseThePoisonRecord() throws Exception {
        String poison = "database-outage-" + UUID.randomUUID();
        UUID valid = UUID.randomUUID();
        jdbc.execute("CREATE SEQUENCE event_quarantine_failure_count");
        jdbc.execute(
                "CREATE FUNCTION fail_quarantine_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.payload='"
                        + poison
                        + "' AND NEW.consumer_id='live' THEN PERFORM nextval('event_quarantine_failure_count'); RAISE EXCEPTION 'injected quarantine storage failure'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_quarantine_acceptance BEFORE INSERT ON domain_event_quarantine FOR EACH ROW EXECUTE FUNCTION fail_quarantine_acceptance()");
        try {
            kafka.send(KafkaTopics.ORDERS_EVENTS_ORDER, 0, "storage-failure", poison).get();
            kafka.send(KafkaTopics.ORDERS_EVENTS_ORDER, 0, "storage-failure", payload(valid)).get();
            await().atMost(Duration.ofSeconds(15))
                    .until(
                            () ->
                                    Boolean.TRUE.equals(
                                            jdbc.queryForObject(
                                                    "SELECT is_called FROM event_quarantine_failure_count",
                                                    Boolean.class)));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM domain_event_inbox WHERE consumer_id='live' AND event_id=?",
                                    Long.class,
                                    valid))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_quarantine_acceptance ON domain_event_quarantine");
            jdbc.execute("DROP FUNCTION fail_quarantine_acceptance()");
            jdbc.execute("DROP SEQUENCE event_quarantine_failure_count");
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM domain_event_quarantine WHERE consumer_id='live' AND payload=?",
                                                    Long.class,
                                                    poison))
                                    .isEqualTo(1L);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM domain_event_inbox WHERE consumer_id='live' AND event_id=?",
                                                    Long.class,
                                                    valid))
                                    .isEqualTo(1L);
                        });
    }

    @Test
    void outboxClaimsPreserveAggregateOrderAcrossConcurrentWorkersAndFailure() throws Exception {
        String schema =
                "event_acceptance_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String query =
                com.example.omp.shared.events.outbox.OutboxRepository.class
                        .getMethod("claimPendingBatch", int.class)
                        .getAnnotation(org.springframework.data.jpa.repository.Query.class)
                        .value()
                        .replace("outbox_event", schema + ".outbox_event");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            jdbc.execute(
                    "CREATE TABLE "
                            + schema
                            + ".outbox_event (id uuid PRIMARY KEY,aggregate_id varchar(255),topic varchar(255),status varchar(20),sequence_id bigserial)");
            UUID first = UUID.randomUUID(),
                    later = UUID.randomUUID(),
                    unrelated = UUID.randomUUID();
            for (var row :
                    java.util.List.of(
                            new Object[] {first, "aggregate-a"},
                            new Object[] {later, "aggregate-a"},
                            new Object[] {unrelated, "aggregate-b"}))
                jdbc.update(
                        "INSERT INTO "
                                + schema
                                + ".outbox_event(id,aggregate_id,topic,status) VALUES (?,?,'topic','PENDING')",
                        row);
            var locked = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try (var worker = Executors.newSingleThreadExecutor()) {
                var firstWorker =
                        worker.submit(
                                () ->
                                        new TransactionTemplate(transactions)
                                                .execute(
                                                        tx -> {
                                                            assertThat(
                                                                            jdbc.queryForList(
                                                                                            query
                                                                                                    .replace(
                                                                                                            ":limit",
                                                                                                            "1"))
                                                                                    .getFirst()
                                                                                    .get("id"))
                                                                    .isEqualTo(first);
                                                            locked.countDown();
                                                            try {
                                                                assertThat(
                                                                                release.await(
                                                                                        10,
                                                                                        java.util
                                                                                                .concurrent
                                                                                                .TimeUnit
                                                                                                .SECONDS))
                                                                        .isTrue();
                                                            } catch (InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new IllegalStateException(e);
                                                            }
                                                            return null;
                                                        }));
                try {
                    assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    var other =
                            new TransactionTemplate(transactions)
                                    .execute(
                                            tx -> jdbc.queryForList(query.replace(":limit", "10")));
                    assertThat(other).extracting(row -> row.get("id")).containsExactly(unrelated);
                } finally {
                    release.countDown();
                }
                firstWorker.get();
            }
            jdbc.update("UPDATE " + schema + ".outbox_event SET status='FAILED' WHERE id=?", first);
            var blocked =
                    new TransactionTemplate(transactions)
                            .execute(tx -> jdbc.queryForList(query.replace(":limit", "10")));
            assertThat(blocked).extracting(row -> row.get("id")).doesNotContain(later);
            jdbc.update(
                    "UPDATE " + schema + ".outbox_event SET status='PUBLISHED' WHERE id=?", first);
            var next =
                    new TransactionTemplate(transactions)
                            .execute(tx -> jdbc.queryForList(query.replace(":limit", "1")));
            assertThat(next.getFirst().get("id")).isEqualTo(later);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}

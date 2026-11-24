package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {"payment.webhook.worker-enabled=false"})
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentWebhookInboxIT {
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("omp")
                    .withUsername("payments_svc")
                    .withPassword("test")
                    .withUrlParam("currentSchema", "payments,public");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("grpc.server.security.enabled", () -> false);
        r.add("grpc.server.port", () -> 0);
        r.add("server.port", () -> 0);
        r.add("management.server.port", () -> 0);
    }

    @Autowired PaymentWebhookInbox inbox;
    @Autowired PaymentTransactionalOps ops;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    Payment pending() {
        return ops.createOrFindPending(
                new PaymentApplicationService.ChargeCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1500,
                        "USD",
                        UUID.randomUUID().toString(),
                        "pm_clientToken"),
                "stripe");
    }

    ProviderWebhookEvent event(Payment p, String type, long amount) {
        var data = mapper.createObjectNode();
        data.put("id", "pi_" + p.getId().toString().replace("-", ""))
                .put("amount", amount)
                .put("currency", "usd")
                .put(
                        "status",
                        type.endsWith("succeeded") ? "succeeded" : "requires_payment_method");
        data.putObject("metadata")
                .put("payment_id", p.getId().toString())
                .put("order_id", p.getOrderId().toString())
                .put("customer_id", p.getCustomerId().toString());
        return new ProviderWebhookEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                type,
                data,
                data.path("id").asText());
    }

    UUID record(ProviderWebhookEvent event) {
        return jdbc.queryForObject(
                "SELECT id FROM payments.provider_event WHERE provider='stripe' AND provider_event_id=?",
                UUID.class,
                event.eventId());
    }

    @Test
    void durableReceiptPrecedesMoneyAndRetriesApplyOneEffect() {
        Payment p = pending();
        var event = event(p, "payment_intent.succeeded", 1500);
        byte[] raw = event.data().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(inbox.accept("stripe", event, raw)).isEqualTo("accepted");
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        UUID id = record(event);
        assertThat(inbox.process(id)).isTrue();
        assertThat(inbox.process(id)).isFalse();
        assertThat(inbox.accept("stripe", event, raw)).isEqualTo("duplicate");
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CONFIRMED);
        assertThatThrownBy(() -> inbox.accept("stripe", event, new byte[] {1}))
                .isInstanceOf(PaymentWebhookInbox.ConflictingEvent.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.provider_event WHERE id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void callbackBeforeTheChargeReplyAttachesOnlyVerifiedCorrelation() {
        Payment p = pending();
        var event = event(p, "payment_intent.succeeded", 1500);
        inbox.accept("stripe", event, event.data().toString().getBytes());
        inbox.process(record(event));
        Payment applied =
                ops.applyChargeResult(p.getId(), ProviderChargeResult.error("Reply was lost"));
        assertThat(applied.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(applied.getProviderReference()).isEqualTo(event.paymentRef());
        var late = event(p, "payment_intent.payment_failed", 1500);
        inbox.accept("stripe", late, late.data().toString().getBytes());
        inbox.process(record(late));
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void outOfOrderFailureCannotHideALaterVerifiedCapture() {
        Payment p = pending();
        var failure = event(p, "payment_intent.payment_failed", 1500);
        inbox.accept("stripe", failure, failure.data().toString().getBytes());
        inbox.process(record(failure));
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        var captured = event(p, "payment_intent.succeeded", 1500);
        inbox.accept("stripe", captured, captured.data().toString().getBytes());
        inbox.process(record(captured));
        assertThat(inbox.process(record(captured))).isFalse();
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id=? AND event_type='payment.confirmed'",
                                Long.class,
                                p.getId().toString()))
                .isEqualTo(1);
    }

    @Test
    void lateCaptureForACancelledOrderQueuesOneRefundAfterInventoryCompensation() {
        Payment p = pending();
        var failure = event(p, "payment_intent.payment_failed", 1500);
        inbox.accept("stripe", failure, failure.data().toString().getBytes());
        inbox.process(record(failure));
        jdbc.update(
                "UPDATE payments.payment SET fulfillment_state='CANCELLING' WHERE id=?", p.getId());
        var captured = event(p, "payment_intent.succeeded", 1500);
        inbox.accept("stripe", captured, captured.data().toString().getBytes());
        assertThatThrownBy(() -> inbox.process(record(captured)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        jdbc.update(
                "UPDATE payments.payment SET fulfillment_state='REFUNDABLE' WHERE id=?", p.getId());
        inbox.process(record(captured));
        assertThat(inbox.process(record(captured))).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.refund WHERE payment_id=? AND status='PENDING'",
                                Long.class,
                                p.getId()))
                .isEqualTo(1);
        assertThat(payments.findById(p.getId()).orElseThrow().getFulfillmentState())
                .isEqualTo("REFUNDABLE");
    }

    @Test
    void unrelatedAmountsRemainDurableWithoutMovingMoney() {
        Payment p = pending();
        var event = event(p, "payment_intent.succeeded", 1600);
        inbox.accept("stripe", event, event.data().toString().getBytes());
        UUID id = record(event);
        assertThatThrownBy(() -> inbox.process(id)).isInstanceOf(IllegalArgumentException.class);
        inbox.failed(id);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM payments.provider_event WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payments.provider_event WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("RECEIVED");
    }

    @Test
    void concurrentOperatorReplayHasOneAuditAndDoesNotExposePayloads() throws Exception {
        Payment p = pending();
        var event = event(p, "payment_intent.succeeded", 1600);
        inbox.accept("stripe", event, new byte[] {1});
        UUID id = record(event);
        jdbc.update(
                "UPDATE payments.provider_event SET status='ATTENTION',attempts=20 WHERE id=?", id);
        assertThat(
                        inbox.attention().stream()
                                .filter(row -> id.equals(row.get("id")))
                                .findFirst()
                                .orElseThrow())
                .doesNotContainKey("payload")
                .doesNotContainKey("body_sha256");
        var gate = new CountDownLatch(1);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    try {
                                        gate.await();
                                        inbox.replay(
                                                id, "admin", "Investigated provider correlation");
                                        successes.incrementAndGet();
                                    } catch (PaymentWebhookInbox.ConflictingEvent expected) {
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }));
            gate.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(successes.get()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.provider_event_replay_audit WHERE event_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void paymentMethodsCannotChangeOrCrossCustomerBoundaries() {
        Payment p = pending();
        assertThatThrownBy(
                        () ->
                                ops.bindPaymentMethod(
                                        p.getId(), p.getCustomerId(), "pm_other", "stripe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ops.bindPaymentMethod(
                                        p.getId(), UUID.randomUUID(), "pm_clientToken", "stripe"))
                .isInstanceOf(PaymentApplicationService.PaymentNotFoundException.class);
        assertThat(
                        ops.bindPaymentMethod(
                                        p.getId(), p.getCustomerId(), "pm_clientToken", "stripe")
                                .getPaymentMethodId())
                .isEqualTo("pm_clientToken");
    }
}

package com.example.omp.notification.webhook;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import com.example.omp.notification.AbstractNotificationTest;
import com.example.omp.notification.support.TestTokens;
import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.dlq.WebhookDlqRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * End-to-end test of the outbound webhook pipeline:
 *
 * <p>1. A subscription is registered directly (via repository). 2. A domain event is published to
 * the payments topic. 3. The matcher fans out, the delivery worker POSTs to a local mock
 * subscriber, and the attempt is recorded as SUCCESS.
 *
 * <p>Uses PostgreSQL/Redis containers and embedded Kafka; a JDK HttpServer stands in for the
 * subscriber endpoint.
 */
class WebhookDeliveryEndToEndIT extends AbstractNotificationTest {

    @Autowired WebhookSubscriptionRepository subscriptions;
    @Autowired WebhookDeliveryAttemptRepository attempts;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired WebhookDlqRepository deadLetters;
    @LocalServerPort int port;

    static HttpServer subscriber;
    static int subscriberPort;

    record Request(String body, Map<String, List<String>> headers) {}

    static List<Request> requests;
    static final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeAll
    static void startSubscriber() throws IOException {
        requests = new CopyOnWriteArrayList<>();

        subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriberPort = subscriber.getAddress().getPort();
        subscriber.createContext(
                "/hook",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    Map<String, List<String>> headers =
                            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    exchange.getRequestHeaders()
                            .forEach((key, values) -> headers.put(key, List.copyOf(values)));
                    requests.add(new Request(body, headers));
                    exchange.sendResponseHeaders(responseStatus.get(), 0);
                    exchange.getResponseBody().close();
                });
        subscriber.start();
    }

    @AfterAll
    static void stopSubscriber() {
        if (subscriber != null) subscriber.stop(0);
    }

    @BeforeEach
    void resetSubscriber() {
        requests.clear();
        responseStatus.set(200);
    }

    @Test
    void permanentFailureIsAuditedAndAdminCanReplayWithStableDeliveryIdentity() throws Exception {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(SubscriptionOwnerType.CUSTOMER);
        sub.setOwnerId(TestTokens.ALICE_CUSTOMER_ID);
        sub.setName("replay-test");
        sub.setTargetUrl("http://127.0.0.1:" + subscriberPort + "/hook");
        sub.setSecret("abcdefghijklmnopqrstuvwxyz0123456789");
        sub.setEventTypes(List.of("payment.failed"));
        subscriptions.save(sub);
        responseStatus.set(410);
        String eventId = UUID.randomUUID().toString();
        String payload =
                objectMapper.writeValueAsString(
                        Map.of(
                                "eventId",
                                eventId,
                                "eventType",
                                "payment.failed",
                                "occurredAt",
                                "2026-01-01T00:00:00Z",
                                "schemaVersion",
                                1,
                                "aggregateId",
                                UUID.randomUUID().toString(),
                                "data",
                                Map.of("paymentId", UUID.randomUUID().toString())));
        kafkaTemplate.send("payments.events.payment", eventId, payload).get(10, SECONDS);
        await().atMost(30, SECONDS)
                .untilAsserted(
                        () -> {
                            assertThat(requests).hasSize(1);
                            var deliveryId =
                                    UUID.fromString(
                                            requests.get(0)
                                                    .headers()
                                                    .get("X-Webhook-Delivery-Id")
                                                    .get(0));
                            var pending = deadLetters.findByDeliveryId(deliveryId);
                            assertThat(pending).isPresent();
                            var dead = pending.orElseThrow();
                            assertThat(dead.getEventId()).isEqualTo(eventId);
                            assertThat(dead.getFinalStatus()).isEqualTo("FAILED_PERMANENT");
                            assertThat(dead.getAttempts()).isEqualTo(1);
                            assertThat(dead.getReason()).isEqualTo("Permanent HTTP 410");
                        });
        UUID deliveryId =
                UUID.fromString(requests.get(0).headers().get("X-Webhook-Delivery-Id").get(0));
        given().port(port)
                .auth()
                .oauth2(TestTokens.admin())
                .get("/api/v1/webhook-dlq")
                .then()
                .statusCode(200)
                .body("content.deliveryId", hasItem(deliveryId.toString()));
        given().port(port)
                .auth()
                .oauth2(TestTokens.alice())
                .queryParam("subscriptionId", sub.getId())
                .get("/api/v1/webhook-deliveries")
                .then()
                .statusCode(200)
                .body("status", contains("FAILED_PERMANENT"))
                .body("responseStatus", contains(410));
        responseStatus.set(200);
        given().port(port)
                .auth()
                .oauth2(TestTokens.admin())
                .post("/api/v1/webhook-dlq/" + deliveryId + "/replay")
                .then()
                .statusCode(200)
                .body("deliveryId", equalTo(deliveryId.toString()))
                .body("replayedBy", equalTo(TestTokens.ADMIN_ID.toString()));
        await().atMost(20, SECONDS)
                .untilAsserted(
                        () -> {
                            assertThat(
                                            attempts.existsByDeliveryIdAndStatus(
                                                    deliveryId, DeliveryStatus.SUCCESS))
                                    .isTrue();
                            assertThat(requests).hasSize(2);
                            assertThat(
                                            subscriptions
                                                    .findById(sub.getId())
                                                    .orElseThrow()
                                                    .getFailureCount())
                                    .isZero();
                        });
        assertThat(requests.get(1).headers().get("X-Webhook-Delivery-Id"))
                .containsExactly(deliveryId.toString());
        assertThat(deadLetters.findByDeliveryId(deliveryId).orElseThrow().getReplayedAt())
                .isNotNull();
        given().port(port)
                .auth()
                .oauth2(TestTokens.admin())
                .post("/api/v1/webhook-dlq/" + deliveryId + "/replay")
                .then()
                .statusCode(409);
        given().port(port)
                .auth()
                .oauth2(TestTokens.admin())
                .post("/api/v1/webhook-dlq/" + UUID.randomUUID() + "/replay")
                .then()
                .statusCode(404);
        given().port(port)
                .auth()
                .oauth2(TestTokens.admin())
                .queryParam("includeReplayed", true)
                .get("/api/v1/webhook-dlq")
                .then()
                .statusCode(200)
                .body("content.deliveryId", hasItem(deliveryId.toString()));
        given().port(port)
                .auth()
                .oauth2(TestTokens.admin())
                .delete("/api/v1/webhook-dlq/" + deliveryId)
                .then()
                .statusCode(204);
        assertThat(deadLetters.findByDeliveryId(deliveryId)).isEmpty();
    }

    @Test
    void payment_confirmed_event_reaches_subscriber() throws Exception {
        // 1. Register a subscription pointing at the mock endpoint.
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(SubscriptionOwnerType.CUSTOMER);
        sub.setOwnerId(UUID.randomUUID());
        sub.setName("e2e-test");
        sub.setTargetUrl("http://127.0.0.1:" + subscriberPort + "/hook");
        sub.setSecret("abcdefghijklmnopqrstuvwxyz0123456789");
        sub.setEventTypes(List.of("payment.confirmed"));
        sub.setEnabled(true);
        subscriptions.save(sub);

        // 2. Publish a domain event to the payments topic.
        String eventId = UUID.randomUUID().toString();
        String payload =
                objectMapper.writeValueAsString(
                        Map.of(
                                "eventId",
                                eventId,
                                "eventType",
                                "payment.confirmed",
                                "aggregateId",
                                UUID.randomUUID().toString(),
                                "occurredAt",
                                "2026-01-01T00:00:00Z",
                                "schemaVersion",
                                1,
                                "data",
                                Map.of("paymentId", UUID.randomUUID().toString())));
        var metadata =
                kafkaTemplate
                        .send("payments.events.payment", eventId, payload)
                        .get(10, SECONDS)
                        .getRecordMetadata();

        // 3. Await the delivery.
        await().atMost(30, SECONDS)
                .untilAsserted(
                        () -> {
                            assertThat(requests).isNotEmpty();
                            String body = requests.get(0).body();
                            JsonNode node = objectMapper.readTree(body);
                            assertThat(node.path("eventType").asText())
                                    .isEqualTo("payment.confirmed");
                            assertThat(node.path("eventId").asText()).isEqualTo(eventId);
                        });

        // 4. Confirm the attempt was recorded as SUCCESS.
        await().atMost(10, SECONDS)
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all =
                                    attempts.findBySubscriptionIdOrderByScheduledAtDesc(
                                                    sub.getId(),
                                                    org.springframework.data.domain.PageRequest.of(
                                                            0, 10))
                                            .toList();
                            assertThat(all).isNotEmpty();
                            assertThat(all.get(0).getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
                            assertThat(all.get(0).getResponseStatus()).isEqualTo(200);
                            assertThat(all.get(0).getEventId()).isEqualTo(eventId);
                        });

        // 5. Verify HMAC headers were sent.
        Map<String, List<String>> headers = requests.get(0).headers();
        assertThat(headers).containsKey("X-Signature-256");
        assertThat(headers).containsKey("X-Timestamp");
        assertThat(headers).containsKey("X-Webhook-Delivery-Id");
        assertThat(headers.get("X-Webhook-Event")).containsExactly("payment.confirmed");
        assertThat(headers.get("X-Webhook-Id")).containsExactly(eventId);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sub.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = headers.get("X-Timestamp").get(0) + "." + requests.get(0).body();
        String signature =
                "sha256="
                        + HexFormat.of()
                                .formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        assertThat(headers.get("X-Signature-256")).containsExactly(signature);
        // Completion includes committed input offsets, so a restart does not replay every record.
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(10, SECONDS)
                    .untilAsserted(
                            () -> {
                                var events =
                                        admin.listConsumerGroupOffsets(
                                                        "notification-service-test-outbound-webhook")
                                                .partitionsToOffsetAndMetadata()
                                                .get(5, SECONDS);
                                var offset =
                                        events.get(
                                                new TopicPartition(
                                                        metadata.topic(), metadata.partition()));
                                assertThat(offset).isNotNull();
                                assertThat(offset.offset()).isGreaterThan(metadata.offset());
                                var deliveries =
                                        admin.listConsumerGroupOffsets(
                                                        "notification-service-test-webhook-delivery")
                                                .partitionsToOffsetAndMetadata()
                                                .get(5, SECONDS);
                                assertThat(deliveries.entrySet())
                                        .anySatisfy(
                                                entry -> {
                                                    assertThat(entry.getKey().topic())
                                                            .isEqualTo(
                                                                    WebhookKafkaTopics.DELIVERIES);
                                                    assertThat(entry.getValue().offset())
                                                            .isPositive();
                                                });
                            });
        }
    }
}

package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.delivery.WebhookSigner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbound webhook — success path.
 *
 * <p>Verifies: 1. A matching subscription receives exactly one POST for the event. 2. The delivery
 * attempt is recorded as SUCCESS. 3. The request carries the correct headers (X-Signature-256,
 * X-Timestamp, X-Webhook-Id, X-Webhook-Delivery-Id, X-Webhook-Event). 4. The signature verifies
 * against the subscription's secret using the same scheme the subscriber is expected to use. 5. The
 * payload envelope has the expected shape.
 */
class OutboundWebhookSuccessIT extends OutboundWebhookBase {

    @Autowired WebhookSigner signer;

    @Test
    void payment_confirmed_is_delivered_and_signed() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(200, "ok");

        String eventId = "evt-success-" + java.util.UUID.randomUUID();
        publishEvent(
                "payments.events.payment",
                eventId,
                "payment.confirmed",
                "{\"paymentId\":\"p-123\",\"amountMinor\":1500,\"currency\":\"USD\"}");

        // 1. A single POST arrives.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        // 2. Attempt recorded SUCCESS.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(1);
                            assertThat(all.get(0).getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
                            assertThat(all.get(0).getResponseStatus()).isEqualTo(200);
                        });

        // 3. Headers present and well-formed.
        String sigHeader = subscriber.header(0, "X-Signature-256");
        String tsHeader = subscriber.header(0, "X-Timestamp");
        String webhookId = subscriber.header(0, "X-Webhook-Id");
        String deliveryId = subscriber.header(0, "X-Webhook-Delivery-Id");
        String eventHeader = subscriber.header(0, "X-Webhook-Event");

        assertThat(sigHeader).startsWith("sha256=");
        assertThat(tsHeader).matches("\\d+");
        assertThat(webhookId).isEqualTo(eventId);
        assertThat(deliveryId).isNotBlank();
        assertThat(eventHeader).isEqualTo("payment.confirmed");

        // 4. Signature verifies with the subscription's secret.
        long ts = Long.parseLong(tsHeader);
        String body = subscriber.bodies.get(0);
        boolean verified = signer.verify(sub.getSecret(), ts, body, sigHeader);
        assertThat(verified).as("HMAC signature must verify").isTrue();

        // 5. Payload envelope shape.
        JsonNode payload = subscriber.bodyAsJson(0);
        assertThat(payload.path("eventId").asText()).isEqualTo(eventId);
        assertThat(payload.path("eventType").asText()).isEqualTo("payment.confirmed");
        assertThat(payload.path("subscriptionId").asText()).isEqualTo(sub.getId().toString());
        assertThat(payload.path("deliveryId").asText()).isEqualTo(deliveryId);
        assertThat(payload.path("attempt").asInt()).isEqualTo(1);
        assertThat(payload.has("occurredAt")).isTrue();
        assertThat(payload.has("data")).isTrue();
    }

    @Test
    void multiple_subscriptions_all_receive_the_event() {
        WebhookSubscription a = createSubscription("payment.confirmed");
        WebhookSubscription b = createSubscription("payment.confirmed");

        subscriber.enqueueResponse(200);
        subscriber.enqueueResponse(200);

        publishEvent(
                "payments.events.payment",
                "evt-fanout-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-fan\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(2));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(countAttempts(a.getId(), DeliveryStatus.SUCCESS))
                                    .isEqualTo(1);
                            assertThat(countAttempts(b.getId(), DeliveryStatus.SUCCESS))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void non_matching_subscription_receives_nothing() {
        WebhookSubscription orderOnly = createSubscription("order.placed");

        publishEvent(
                "payments.events.payment",
                "evt-nomatch-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-nomatch\"}");

        // Give the pipeline a moment to (not) deliver.
        org.awaitility.Awaitility.await()
                .pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isZero());

        assertThat(attemptsFor(orderOnly.getId())).isEmpty();
    }

    @Test
    void duplicate_event_delivery_id_is_not_redelivered_after_success() {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(200);

        String eventId = "evt-dedup-" + java.util.UUID.randomUUID();
        publishEvent(
                "payments.events.payment",
                eventId,
                "payment.confirmed",
                "{\"paymentId\":\"p-dedup\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        // Re-publish the same event (same eventId). The matcher generates the
        // same deliveryId, and the worker skips it because a SUCCESS attempt
        // already exists.
        publishEvent(
                "payments.events.payment",
                eventId,
                "payment.confirmed",
                "{\"paymentId\":\"p-dedup\"}");

        org.awaitility.Awaitility.await()
                .pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));
    }

    @Test
    void delivery_attempts_contain_signed_payload_and_response_body() {
        WebhookSubscription sub = createSubscription("order.placed");
        subscriber.enqueueResponse(200, "{\"received\":true}");

        publishEvent(
                "orders.events.order",
                "evt-attempt-" + java.util.UUID.randomUUID(),
                "order.placed",
                "{\"orderId\":\"o-42\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(1);
                            WebhookDeliveryAttempt a = all.get(0);
                            assertThat(a.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
                            assertThat(a.getPayload()).isNotBlank();
                            assertThat(a.getSignature()).startsWith("sha256=");
                            assertThat(a.getResponseBody()).isEqualTo("{\"received\":true}");
                            assertThat(a.getEventId()).startsWith("evt-attempt-");
                            assertThat(a.getEventType()).isEqualTo("order.placed");
                        });
    }
}

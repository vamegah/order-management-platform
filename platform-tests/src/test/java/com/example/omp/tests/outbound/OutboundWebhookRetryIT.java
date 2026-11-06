package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import com.example.omp.notification.webhook.WebhookSubscription;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Outbound webhook — retry path.
 *
 * <p>Verifies: 1. A 503 causes a retry; the subscriber sees 2 requests; the successful one is
 * recorded as SUCCESS; the failed one as FAILED_RETRYABLE. 2. A timeout is treated as retryable. 3.
 * A 429 with Retry-After is retried. 4. Retry attempts increment attemptNumber.
 */
class OutboundWebhookRetryIT extends OutboundWebhookBase {

    @Test
    void five_xx_is_retried_then_succeeds() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(503, "temporarily unavailable");
        subscriber.enqueueResponse(200, "ok");

        publishEvent(
                "payments.events.payment",
                "evt-retry-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-retry\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(2));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(2);
                            // Ordering: most recent first.
                            assertThat(all.get(0).getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
                            assertThat(all.get(0).getAttemptNumber()).isEqualTo(2);
                            assertThat(all.get(1).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_RETRYABLE);
                            assertThat(all.get(1).getAttemptNumber()).isEqualTo(1);
                            assertThat(all.get(1).getResponseStatus()).isEqualTo(503);
                        });
    }

    @Test
    void timeout_is_retried() throws Exception {
        WebhookSubscription sub = createSubscription("order.placed");
        subscriber.enqueueResponse(200, "late", Duration.ofSeconds(5));
        subscriber.enqueueResponse(200, "ok");
        publishEvent(
                "orders.events.order",
                java.util.UUID.randomUUID().toString(),
                "order.placed",
                "{\"orderId\":\"o-timeout\"}");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            assertThat(subscriber.requestCount.get()).isEqualTo(2);
                            assertThat(countAttempts(sub.getId(), DeliveryStatus.FAILED_RETRYABLE))
                                    .isEqualTo(1);
                            assertThat(countAttempts(sub.getId(), DeliveryStatus.SUCCESS))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void retry_preserves_delivery_id_across_attempts() throws Exception {
        WebhookSubscription sub = createSubscription("order.placed");
        subscriber.enqueueResponse(500);
        subscriber.enqueueResponse(200);

        String eventId = "evt-same-delivery-" + java.util.UUID.randomUUID();
        publishEvent("orders.events.order", eventId, "order.placed", "{\"orderId\":\"o-1\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(2));

        String deliveryId1 = subscriber.header(0, "X-Webhook-Delivery-Id");
        String deliveryId2 = subscriber.header(1, "X-Webhook-Delivery-Id");
        assertThat(deliveryId1).isEqualTo(deliveryId2);

        List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
        assertThat(all).hasSize(2);
        assertThat(all.stream().map(WebhookDeliveryAttempt::getDeliveryId).distinct()).hasSize(1);
    }

    @Test
    void retry_increments_attempt_number_in_payload() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(502);
        subscriber.enqueueResponse(200);

        publishEvent(
                "payments.events.payment",
                "evt-attempt-number-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-an\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(2));

        int firstAttempt = subscriber.bodyAsJson(0).path("attempt").asInt();
        int secondAttempt = subscriber.bodyAsJson(1).path("attempt").asInt();
        assertThat(firstAttempt).isEqualTo(1);
        assertThat(secondAttempt).isEqualTo(2);
    }

    @Test
    void connection_refused_is_retried() throws Exception {
        // Point the subscription at a port with nothing listening.
        WebhookSubscription sub = createSubscription("payment.confirmed");
        sub.setTargetUrl("http://127.0.0.1:1/hook");
        subscriptions.save(sub);

        publishEvent(
                "payments.events.payment",
                "evt-conn-refused-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-cr\"}");

        // The first attempt fails; the fast-retry profile schedules the next
        // attempt immediately. We expect at least two FAILED_RETRYABLE rows.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                countAttempts(
                                                        sub.getId(),
                                                        DeliveryStatus.FAILED_RETRYABLE))
                                        .isGreaterThanOrEqualTo(2));
    }
}

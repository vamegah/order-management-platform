package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.dlq.WebhookDlqEntry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Outbound webhook — permanent failure path.
 *
 * <p>Verifies: 1. 404 -> FAILED_PERMANENT, no retry. 2. 400 -> FAILED_PERMANENT. 3. Invalid stored
 * URL -> FAILED_PERMANENT. 4. Permanent failure lands in the DLQ immediately. 5. 401/403 are
 * treated as retryable (subscriber may fix auth).
 */
class OutboundWebhookPermanentFailureIT extends OutboundWebhookBase {

    @Test
    void four_oh_four_is_permanent_and_no_retry() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(404, "not found");

        publishEvent(
                "payments.events.payment",
                "evt-404-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-404\"}");

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(1);
                            assertThat(all.get(0).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_PERMANENT);
                            assertThat(all.get(0).getResponseStatus()).isEqualTo(404);
                        });

        // No retry should arrive.
        org.awaitility.Awaitility.await()
                .pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        // DLQ entry created.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            List<WebhookDlqEntry> dlq = dlqRepository.findAll();
                            assertThat(dlq).hasSize(1);
                            assertThat(dlq.get(0).getFinalStatus()).isEqualTo("FAILED_PERMANENT");
                            assertThat(dlq.get(0).getSubscriptionId()).isEqualTo(sub.getId());
                        });
    }

    @Test
    void four_hundred_is_permanent() throws Exception {
        WebhookSubscription sub = createSubscription("order.placed");
        subscriber.enqueueResponse(400, "bad request");

        publishEvent(
                "orders.events.order",
                "evt-400-" + java.util.UUID.randomUUID(),
                "order.placed",
                "{\"orderId\":\"o-400\"}");

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(1);
                            assertThat(all.get(0).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_PERMANENT);
                        });
    }

    @Test
    void unauthorized_is_retryable_because_subscriber_may_fix_auth() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(401, "unauthorized");
        subscriber.enqueueResponse(200, "ok");

        publishEvent(
                "payments.events.payment",
                "evt-401-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-401\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(2));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(2);
                            assertThat(all.get(0).getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
                            assertThat(all.get(1).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_RETRYABLE);
                            assertThat(all.get(1).getResponseStatus()).isEqualTo(401);
                        });
    }

    @Test
    void forbidden_is_retryable() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(403, "forbidden");
        subscriber.enqueueResponse(200, "ok");

        publishEvent(
                "payments.events.payment",
                "evt-403-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-403\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(2));
    }

    @Test
    void invalid_url_is_permanent_and_lands_in_dlq() {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        // Corrupt stored URLs fail deterministically without depending on external DNS.
        sub.setTargetUrl("http://[broken");
        subscriptions.save(sub);

        publishEvent(
                "payments.events.payment",
                "evt-tls-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-tls\"}");

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> {
                            List<WebhookDeliveryAttempt> all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(1);
                            assertThat(all.get(0).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_PERMANENT);
                            assertThat(all.get(0).getErrorMessage()).contains("Invalid URL");
                            assertThat(dlqRepository.findAll()).hasSize(1);
                            assertThat(subscriber.requestCount.get()).isZero();
                        });
    }
}

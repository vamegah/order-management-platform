package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.delivery.RetryScheduler;
import com.example.omp.notification.webhook.dlq.WebhookDlqEntry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Outbound webhook — retry exhaustion.
 *
 * <p>The subscriber returns 503 for every request. After max attempts, the delivery lands in the
 * DLQ with reason "retries_exhausted".
 *
 * <p>With the fast-retry profile, all attempts happen quickly (no backoff). The worker itself
 * publishes to retry topics; each retry consumes and attempts immediately.
 */
class OutboundWebhookRetryExhaustionIT extends OutboundWebhookBase {

    @Test
    void six_failures_lead_to_dlq() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");

        // Fill the response queue with far more 503s than attempts.
        for (int i = 0; i < 20; i++) {
            subscriber.enqueueResponse(503, "unavailable");
        }

        publishEvent(
                "payments.events.payment",
                "evt-exhaust-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-exhaust\"}");

        int maxAttempts = (int) RetryScheduler.maxAttempts(); // 6

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () -> assertThat(subscriber.requestCount.get()).isEqualTo(maxAttempts));

        // All attempts recorded FAILED_RETRYABLE.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            List<com.example.omp.notification.webhook.WebhookDeliveryAttempt> all =
                                    attemptsFor(sub.getId());
                            assertThat(all).hasSize(maxAttempts);
                            assertThat(all)
                                    .allMatch(
                                            a -> a.getStatus() == DeliveryStatus.FAILED_RETRYABLE);
                        });

        // DLQ entry with the "retries_exhausted" reason.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            List<WebhookDlqEntry> dlq = dlqRepository.findAll();
                            assertThat(dlq).hasSize(1);
                            WebhookDlqEntry entry = dlq.get(0);
                            assertThat(entry.getFinalStatus()).isEqualTo("FAILED_RETRYABLE");
                            assertThat(entry.getReason()).contains("Retries exhausted");
                            assertThat(entry.getAttempts()).isEqualTo(maxAttempts);
                        });
    }
}

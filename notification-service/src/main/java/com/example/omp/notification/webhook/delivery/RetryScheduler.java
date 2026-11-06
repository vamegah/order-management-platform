package com.example.omp.notification.webhook.delivery;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Publishes a failed delivery to the appropriate retry topic, with the next attempt's scheduled
 * time encoded in the message.
 *
 * <p>The delay itself is applied by the delivery worker: it sleeps until scheduledAtMillis before
 * attempting. This keeps the retry topology simple (one topic per retry level) while still honoring
 * jitter.
 */
@Component
public class RetryScheduler {

    /** Base delays in millis for attempts 2..6. Index 0 corresponds to attempt 2. */
    private static final long[] BASE_DELAY_MS = {
        /* attempt 2 */ 60_000L, // 1 minute
        /* attempt 3 */ 5 * 60_000L, // 5 minutes
        /* attempt 4 */ 30 * 60_000L, // 30 minutes
        /* attempt 5 */ 2 * 60 * 60_000L, // 2 hours
        /* attempt 6 */ 24 * 60 * 60_000L // 24 hours
    };

    private static final double JITTER_FRACTION = 0.10;

    private final WebhookDeliveryPublisher publisher;
    private final WebhookMetrics metrics;

    public RetryScheduler(WebhookDeliveryPublisher publisher, WebhookMetrics metrics) {
        this.publisher = publisher;
        this.metrics = metrics;
    }

    /**
     * Schedules the next attempt for a delivery.
     *
     * @param currentAttempt the attempt that just failed (1-based)
     * @param deliveryId stable delivery id
     * @param subscriptionId subscriber
     * @param eventId source event id
     * @param eventType source event type
     * @param retryAfterMs optional override (e.g., from a 429 Retry-After header)
     */
    public void scheduleRetry(
            int currentAttempt,
            java.util.UUID deliveryId,
            java.util.UUID subscriptionId,
            String eventId,
            String eventType,
            Long retryAfterMs) {

        int nextAttempt = currentAttempt + 1;
        long baseDelayMs = retryAfterMs != null ? retryAfterMs : delayForAttempt(currentAttempt);
        long delayMs = applyJitter(baseDelayMs);
        long scheduledAt = System.currentTimeMillis() + delayMs;

        DeliveryMessage message =
                new DeliveryMessage(
                        deliveryId, subscriptionId, eventId, eventType, nextAttempt, scheduledAt);

        String topic = WebhookKafkaTopics.retryTopicForAttempt(nextAttempt);
        publisher.publish(topic, message);
        metrics.recordRetryScheduled(nextAttempt);
    }

    private static long delayForAttempt(int attemptJustFailed) {
        int idx = attemptJustFailed - 1; // attempt 1 -> index 0
        if (idx < 0 || idx >= BASE_DELAY_MS.length) {
            throw new IllegalArgumentException("No base delay for attempt " + attemptJustFailed);
        }
        return BASE_DELAY_MS[idx];
    }

    private static long applyJitter(long delayMs) {
        long jitter = (long) (delayMs * JITTER_FRACTION);
        if (jitter <= 0) {
            return delayMs;
        }
        long offset = ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Math.max(0, delayMs + offset);
    }

    /** Exposed for tests. */
    public static long maxAttempts() {
        return BASE_DELAY_MS.length + 1; // 6 attempts total
    }
}

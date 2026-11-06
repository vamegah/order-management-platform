package com.example.omp.testsupport;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test replacement for RetryScheduler.
 *
 * <p>Publishes the retry message to the same topic the production scheduler would use, but with
 * scheduledAtMillis = now (no delay, no jitter). This makes retry tests fast and deterministic
 * while still exercising the retry-topic plumbing.
 *
 * <p>Activated with @ActiveProfiles("test-fast-retry").
 */
@Component
@Primary
@Profile("test-fast-retry")
public class TestRetryScheduler
        extends com.example.omp.notification.webhook.delivery.RetryScheduler {

    private final WebhookDeliveryPublisher publisher;
    private final WebhookMetrics metrics;

    public TestRetryScheduler(WebhookDeliveryPublisher publisher, WebhookMetrics metrics) {
        super(publisher, metrics);
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Override
    public void scheduleRetry(
            int currentAttempt,
            UUID deliveryId,
            UUID subscriptionId,
            String eventId,
            String eventType,
            Long retryAfterMs) {

        int nextAttempt = currentAttempt + 1;
        long scheduledAt = System.currentTimeMillis(); // immediate

        DeliveryMessage message =
                new DeliveryMessage(
                        deliveryId, subscriptionId, eventId, eventType, nextAttempt, scheduledAt);

        String topic = WebhookKafkaTopics.retryTopicForAttempt(nextAttempt);
        publisher.publish(topic, message);
        metrics.recordRetryScheduled(nextAttempt);
    }
}

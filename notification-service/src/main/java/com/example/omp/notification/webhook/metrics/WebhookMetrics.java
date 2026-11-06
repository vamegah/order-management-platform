package com.example.omp.notification.webhook.metrics;

import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Custom metrics for the outbound webhook pipeline.
 *
 * <p>Cardinality note: subscription_id is hashed before use as a label to avoid unbounded
 * cardinality (a subscription per customer is fine; but in a large deployment we still want to
 * protect the registry).
 */
@Component
public class WebhookMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> deliveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> retries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> dlq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> matched = new ConcurrentHashMap<>();
    private final Counter dlqReplays;
    private final Counter subscriptionsDisabled;
    private final AtomicLong activeSubscriptions = new AtomicLong();
    private final AtomicLong disabledSubscriptions = new AtomicLong();

    public WebhookMetrics(
            MeterRegistry registry, WebhookSubscriptionRepository subscriptionRepository) {
        this.registry = registry;

        this.dlqReplays =
                Counter.builder("webhook_outbound_dlq_replays_total")
                        .description("Number of manual DLQ replays")
                        .register(registry);

        this.subscriptionsDisabled =
                Counter.builder("webhook_outbound_subscriptions_disabled_total")
                        .description("Number of subscriptions auto-disabled by the circuit breaker")
                        .register(registry);

        Gauge.builder("webhook_outbound_active_subscriptions", activeSubscriptions, AtomicLong::get)
                .description("Currently enabled webhook subscriptions")
                .register(registry);

        Gauge.builder(
                        "webhook_outbound_disabled_subscriptions",
                        disabledSubscriptions,
                        AtomicLong::get)
                .description("Currently disabled webhook subscriptions")
                .register(registry);

        // Refresh the gauges periodically on scrape via a supplier is the usual
        // approach; here we snapshot on each metric access by querying the repo.
        Gauge.builder(
                        "webhook_outbound_active_subscriptions_db",
                        subscriptionRepository,
                        WebhookSubscriptionRepository::countByEnabledTrue)
                .description("Currently enabled webhook subscriptions (from DB)")
                .register(registry);

        Gauge.builder(
                        "webhook_outbound_disabled_subscriptions_db",
                        subscriptionRepository,
                        WebhookSubscriptionRepository::countByEnabledFalse)
                .description("Currently disabled webhook subscriptions (from DB)")
                .register(registry);
    }

    public void recordDelivery(String eventType, String result) {
        deliveries
                .computeIfAbsent(
                        eventType + "|" + result,
                        k ->
                                Counter.builder("webhook_outbound_deliveries_total")
                                        .description(
                                                "Outbound webhook deliveries by event type and result")
                                        .tag("event_type", eventType)
                                        .tag("result", result)
                                        .register(registry))
                .increment();
    }

    public void recordMatched(String eventType) {
        matched.computeIfAbsent(
                        eventType,
                        k ->
                                Counter.builder("webhook_outbound_matched_total")
                                        .description(
                                                "Events that matched at least one subscription")
                                        .tag("event_type", eventType)
                                        .register(registry))
                .increment();
    }

    public void recordRetryScheduled(int attemptNumber) {
        retries.computeIfAbsent(
                        Integer.toString(attemptNumber),
                        k ->
                                Counter.builder("webhook_outbound_retries_total")
                                        .description("Retries scheduled by attempt number")
                                        .tag("attempt_number", k)
                                        .register(registry))
                .increment();
    }

    public void recordDlq(String reason) {
        dlq.computeIfAbsent(
                        reason,
                        k ->
                                Counter.builder("webhook_outbound_dlq_total")
                                        .description("Deliveries dead-lettered by reason")
                                        .tag("reason", k)
                                        .register(registry))
                .increment();
    }

    public void recordDlqReplay() {
        dlqReplays.increment();
    }

    public void recordSubscriptionDisabled() {
        subscriptionsDisabled.increment();
    }

    public void recordDeliveryDuration(UUID subscriptionId, WebhookDeliveryAttempt attempt) {
        if (attempt.getStartedAt() == null || attempt.getCompletedAt() == null) {
            return;
        }
        long millis = Duration.between(attempt.getStartedAt(), attempt.getCompletedAt()).toMillis();
        String hashed = Integer.toHexString(subscriptionId.hashCode());
        Timer.builder("webhook_outbound_delivery_duration_seconds")
                .description("Outbound webhook delivery duration")
                .tag("subscription_id", hashed)
                .register(registry)
                .record(Duration.ofMillis(millis));
    }
}

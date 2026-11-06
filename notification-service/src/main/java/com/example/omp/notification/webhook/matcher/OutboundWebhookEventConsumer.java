package com.example.omp.notification.webhook.matcher;

import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import com.example.omp.shared.events.DomainEventEnvelope;
import com.example.omp.shared.events.DomainEventReader;
import com.example.omp.shared.events.EventRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes domain events and fans them out to matching webhook subscriptions.
 *
 * <p>Amended in Batch 9.4 to: - use the shared DomainEventReader (single envelope parser) - consult
 * EventRegistry to ignore unknown event types (e.g. test events)
 *
 * <p>Payment events (payment.initiated / confirmed / failed / refunded) flow through unchanged
 * because the matcher is event-type agnostic — it queries subscriptions by event_type value.
 */
@Component
public class OutboundWebhookEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OutboundWebhookEventConsumer.class);

    private final SubscriptionMatcher matcher;
    private final DomainEventReader eventReader;
    private final EventRegistry eventRegistry;
    private final WebhookMetrics metrics;

    public OutboundWebhookEventConsumer(
            SubscriptionMatcher matcher,
            DomainEventReader eventReader,
            EventRegistry eventRegistry,
            WebhookMetrics metrics) {
        this.matcher = matcher;
        this.eventReader = eventReader;
        this.eventRegistry = eventRegistry;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = {
                "${webhook.topics.events.order:orders.events.order}",
                "${webhook.topics.events.payment:payments.events.payment}",
                "${webhook.topics.events.inventory:inventory.events.reservation}"
            },
            groupId = "${spring.kafka.consumer.group-id}-outbound-webhook",
            containerFactory = "webhookEventKafkaListenerContainerFactory")
    public void onEvent(String message) {
        try {
            DomainEventEnvelope envelope = eventReader.read(message);

            if (!envelope.isValid()) {
                log.warn("Skipping event with missing eventId/eventType: {}", message);
                return;
            }
            if (!eventRegistry.knows(envelope.eventType())) {
                log.debug("Ignoring unknown event type: {}", envelope.eventType());
                return;
            }

            JsonNode data = envelope.data();
            matcher.matchAndPublish(envelope.eventId(), envelope.eventType(), data);

        } catch (Exception e) {
            log.error("Failed to process inbound domain event for outbound webhooks", e);
            throw new RuntimeException(e);
        }
    }
}

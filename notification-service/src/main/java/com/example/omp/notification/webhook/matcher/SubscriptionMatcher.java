package com.example.omp.notification.webhook.matcher;

import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Given an event (id + type + envelope), finds all enabled subscriptions that match and publishes a
 * WebhookDelivery message for each.
 */
@Component
public class SubscriptionMatcher {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionMatcher.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryPublisher publisher;
    private final DeliveryIdGenerator idGenerator;
    private final WebhookMetrics metrics;

    public SubscriptionMatcher(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryPublisher publisher,
            DeliveryIdGenerator idGenerator,
            WebhookMetrics metrics) {
        this.subscriptionRepository = subscriptionRepository;
        this.publisher = publisher;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
    }

    public void matchAndPublish(String eventId, String eventType, JsonNode envelope) {
        Map<UUID, MatchedSubscription> matches = new LinkedHashMap<>();
        for (String candidate : WebhookEventTypes.matchingTypes(eventType)) {
            for (WebhookSubscription sub :
                    subscriptionRepository.findEnabledByEventType(candidate)) {
                matches.putIfAbsent(sub.getId(), new MatchedSubscription(sub, candidate));
            }
        }

        if (matches.isEmpty()) {
            log.debug("No subscriptions matched event {} ({})", eventId, eventType);
            return;
        }

        for (MatchedSubscription match : matches.values()) {
            WebhookSubscription sub = match.subscription();
            DeliveryMessage message =
                    new DeliveryMessage(
                            idGenerator.generate(sub.getId(), eventId),
                            sub.getId(),
                            eventId,
                            match.eventType(),
                            1,
                            null);
            publisher.publish(message);
            metrics.recordMatched(eventType);
        }

        log.debug(
                "Event {} ({}) fanned out to {} subscriptions", eventId, eventType, matches.size());
    }

    private record MatchedSubscription(WebhookSubscription subscription, String eventType) {}
}

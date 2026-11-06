package com.example.omp.notification.dispatcher;

import com.example.omp.notification.subscription.Subscription;
import com.example.omp.notification.subscription.SubscriptionRegistry;
import com.example.omp.shared.events.BaseEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Dispatches events to local WebSocket subscribers.
 *
 * <p><b>Flow:</b>
 *
 * <ol>
 *   <li>Resolve the event to a (TopicType, resourceId) pair.
 *   <li>Look up subscriptions in the {@link SubscriptionRegistry}.
 *   <li>For each matching subscription, send the event to the destination via the STOMP messaging
 *       template.
 * </ol>
 *
 * <p><b>Fan-out:</b> the STOMP simple broker broadcasts to all sessions subscribed to the
 * destination. Sending one message per destination reaches all sessions on this replica that
 * subscribed to it.
 *
 * <p><b>Idempotency:</b> duplicate events (from Kafka offset re-commit after a crash) are sent
 * twice. Clients see the same state twice, which is harmless for our notification model.
 *
 * <p><b>Why not filter per-session here?</b> The registry is already indexed by (type, resourceId).
 * Only subscriptions matching the event's resource are returned. Additional per-session filtering
 * (e.g., user ownership) is done at SUBSCRIBE time (see {@code OwnershipVerifier}).
 */
@Component
public class EventDispatcherImpl implements EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcherImpl.class);

    private final DestinationResolver resolver;
    private final SubscriptionRegistry registry;
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry metrics;

    public EventDispatcherImpl(
            DestinationResolver resolver,
            SubscriptionRegistry registry,
            SimpMessagingTemplate messagingTemplate,
            MeterRegistry metrics) {
        this.resolver = resolver;
        this.registry = registry;
        this.messagingTemplate = messagingTemplate;
        this.metrics = metrics;
    }

    @Override
    public void dispatch(BaseEvent event) {
        String customerId =
                switch (event) {
                    case com.example.omp.shared.events.OrderEvent order -> order.customerId();
                    case com.example.omp.shared.events.PaymentEvent payment -> payment.customerId();
                    default -> null;
                };
        if (customerId != null) {
            registry
                    .find(
                            com.example.omp.notification.subscription.TopicType.NOTIFICATIONS,
                            customerId)
                    .stream()
                    .map(Subscription::userId)
                    .distinct()
                    .forEach(
                            user ->
                                    messagingTemplate.convertAndSendToUser(
                                            user,
                                            "/queue/notifications",
                                            EventEnvelope.from(event)));
        }
        var resolved = resolver.resolve(event);
        if (resolved.isEmpty()) {
            log.debug("Event {} has no destination mapping; skipping", event.eventType());
            return;
        }

        var dest = resolved.get();
        Set<Subscription> subscriptions = registry.find(dest.type(), dest.resourceId());

        if (subscriptions.isEmpty()) {
            log.debug(
                    "No subscriptions for {}:{} — event {} ignored",
                    dest.type(),
                    dest.resourceId(),
                    event.eventType());
            return;
        }

        // Compose the wire payload.
        EventEnvelope envelope = EventEnvelope.from(event);

        // Send to all subscribers of the destination on this replica.
        String destination = destination(dest.type(), dest.resourceId());
        messagingTemplate.convertAndSend(destination, envelope);

        metrics.counter("websocket.events.dispatched", "destination_type", dest.type().name())
                .increment(subscriptions.size());

        log.debug(
                "Dispatched {} to {} subscribers at {}",
                event.eventType(),
                subscriptions.size(),
                destination);
    }

    private static String destination(
            com.example.omp.notification.subscription.TopicType type, String resourceId) {
        return switch (type) {
            case ORDERS -> "/topic/orders/" + resourceId;
            case INVENTORY -> "/topic/inventory/" + resourceId;
            case PAYMENTS -> "/topic/payments/" + resourceId;
            case NOTIFICATIONS -> "/user/queue/notifications";
        };
    }
}

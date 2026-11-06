package com.example.omp.notification.dispatcher;

import com.example.omp.notification.subscription.TopicType;
import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.InventoryEvent;
import com.example.omp.shared.events.OrderEvent;
import com.example.omp.shared.events.PaymentEvent;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps an event to the (TopicType, resourceId) pair that subscribers use.
 *
 * <p>Used by the dispatcher to look up subscriptions in the {@code SubscriptionRegistry}.
 *
 * <p><b>Mapping:</b>
 *
 * <ul>
 *   <li>{@link OrderEvent} → {@code (ORDERS, orderId)}
 *   <li>{@link InventoryEvent} → {@code (INVENTORY, sku)}
 *   <li>{@link PaymentEvent} → {@code (PAYMENTS, paymentId)}
 * </ul>
 */
@Component
public class DestinationResolver {

    public Optional<Resolved> resolve(BaseEvent event) {
        return switch (event) {
            case OrderEvent oe -> Optional.of(new Resolved(TopicType.ORDERS, oe.orderId()));
            case InventoryEvent ie -> Optional.of(new Resolved(TopicType.INVENTORY, ie.sku()));
            case PaymentEvent pe -> Optional.of(new Resolved(TopicType.PAYMENTS, pe.paymentId()));
            default -> Optional.empty();
        };
    }

    /** The (type, resourceId) pair used to look up subscriptions. */
    public record Resolved(TopicType type, String resourceId) {}
}

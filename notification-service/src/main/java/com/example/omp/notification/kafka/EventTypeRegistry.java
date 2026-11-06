package com.example.omp.notification.kafka;

import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.InventoryEventType;
import com.example.omp.shared.events.InventoryEvents;
import com.example.omp.shared.events.OrderEventType;
import com.example.omp.shared.events.OrderEvents;
import com.example.omp.shared.events.PaymentEventType;
import com.example.omp.shared.events.PaymentEvents;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps event type strings to their concrete Java classes.
 *
 * <p>The single source of truth for "which class handles which event type." Adding a new event type
 * is one line here.
 *
 * <p><b>Unknown types:</b> {@link #lookup} returns null. The deserializer treats null as "ignore
 * this event" — forward compatible.
 */
@Component
public class EventTypeRegistry {

    private final Map<String, Class<? extends BaseEvent>> types;

    public EventTypeRegistry() {
        this.types =
                Map.ofEntries(
                        // Orders
                        Map.entry(OrderEventType.ORDER_CREATED, OrderEvents.OrderCreated.class),
                        Map.entry(OrderEventType.ORDER_PLACED, OrderEvents.OrderPlaced.class),
                        Map.entry(OrderEventType.ORDER_CONFIRMED, OrderEvents.OrderConfirmed.class),
                        Map.entry(OrderEventType.ORDER_SHIPPED, OrderEvents.OrderShipped.class),
                        Map.entry(OrderEventType.ORDER_DELIVERED, OrderEvents.OrderDelivered.class),
                        Map.entry(OrderEventType.ORDER_CANCELLED, OrderEvents.OrderCancelled.class),

                        // Inventory
                        Map.entry(
                                InventoryEventType.RESERVATION_CREATED,
                                InventoryEvents.ReservationCreated.class),
                        Map.entry(
                                InventoryEventType.RESERVATION_COMMITTED,
                                InventoryEvents.ReservationCommitted.class),
                        Map.entry(
                                InventoryEventType.RESERVATION_RELEASED,
                                InventoryEvents.ReservationReleased.class),
                        Map.entry(
                                InventoryEventType.RESERVATION_EXPIRED,
                                InventoryEvents.ReservationExpired.class),

                        // Payments
                        Map.entry(
                                PaymentEventType.PAYMENT_INTENT_CREATED,
                                PaymentEvents.PaymentIntentCreated.class),
                        Map.entry(
                                PaymentEventType.PAYMENT_CONFIRMED,
                                PaymentEvents.PaymentConfirmed.class),
                        Map.entry(
                                PaymentEventType.PAYMENT_CANCELLED,
                                PaymentEvents.PaymentCancelled.class),
                        Map.entry(
                                PaymentEventType.PAYMENT_FAILED, PaymentEvents.PaymentFailed.class),
                        Map.entry(
                                PaymentEventType.PAYMENT_REFUNDED,
                                PaymentEvents.PaymentRefunded.class),
                        Map.entry(
                                PaymentEventType.PAYMENT_PARTIALLY_REFUNDED,
                                PaymentEvents.PaymentPartiallyRefunded.class));
    }

    /** Returns the concrete class for an event type, or null if unknown. */
    public Class<? extends BaseEvent> lookup(String eventType) {
        return types.get(eventType);
    }
}

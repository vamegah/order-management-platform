package com.example.omp.shared.events;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Accepts canonical producer types and the published dotted-name envelope aliases. */
@Component
public class EventRegistry {
    private static final Map<String, Class<? extends BaseEvent>> TYPES =
            Map.ofEntries(
                    Map.entry(OrderEventType.ORDER_CREATED, OrderEvents.OrderCreated.class),
                    Map.entry(OrderEventType.ORDER_PLACED, OrderEvents.OrderPlaced.class),
                    Map.entry(OrderEventType.ORDER_CONFIRMED, OrderEvents.OrderConfirmed.class),
                    Map.entry(OrderEventType.ORDER_SHIPPED, OrderEvents.OrderShipped.class),
                    Map.entry(OrderEventType.ORDER_DELIVERED, OrderEvents.OrderDelivered.class),
                    Map.entry(OrderEventType.ORDER_CANCELLED, OrderEvents.OrderCancelled.class),
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
                    Map.entry(
                            PaymentEventType.PAYMENT_INTENT_CREATED,
                            PaymentEvents.PaymentIntentCreated.class),
                    Map.entry(
                            PaymentEventType.PAYMENT_CONFIRMED,
                            PaymentEvents.PaymentConfirmed.class),
                    Map.entry(
                            PaymentEventType.PAYMENT_CANCELLED,
                            PaymentEvents.PaymentCancelled.class),
                    Map.entry(PaymentEventType.PAYMENT_FAILED, PaymentEvents.PaymentFailed.class),
                    Map.entry(
                            PaymentEventType.PAYMENT_REFUNDED, PaymentEvents.PaymentRefunded.class),
                    Map.entry(
                            PaymentEventType.PAYMENT_PARTIALLY_REFUNDED,
                            PaymentEvents.PaymentPartiallyRefunded.class));

    /** Only code-owned canonical event classes can be deserialized; aliases are envelope-only. */
    public Class<? extends BaseEvent> eventClass(String eventType) {
        return eventType == null ? null : TYPES.get(eventType);
    }

    public static final Set<String> ORDER_EVENTS =
            Set.of(
                    "order.placed",
                    "order.shipped",
                    "order.cancelled",
                    OrderEventType.ORDER_CREATED,
                    OrderEventType.ORDER_PLACED,
                    OrderEventType.ORDER_CONFIRMED,
                    OrderEventType.ORDER_SHIPPED,
                    OrderEventType.ORDER_DELIVERED,
                    OrderEventType.ORDER_CANCELLED);
    public static final Set<String> INVENTORY_EVENTS =
            Set.of(
                    "inventory.reserved",
                    "inventory.released",
                    InventoryEventType.RESERVATION_CREATED,
                    InventoryEventType.RESERVATION_COMMITTED,
                    InventoryEventType.RESERVATION_RELEASED,
                    InventoryEventType.RESERVATION_EXPIRED);
    public static final Set<String> PAYMENT_EVENTS =
            Stream.concat(
                            PaymentEventType.ALL.stream(),
                            Stream.of(
                                    "payment.initiated",
                                    "payment.confirmed",
                                    "payment.failed",
                                    "payment.refunded"))
                    .collect(Collectors.toUnmodifiableSet());
    public static final Set<String> ALL =
            Stream.of(ORDER_EVENTS, INVENTORY_EVENTS, PAYMENT_EVENTS)
                    .flatMap(Set::stream)
                    .collect(Collectors.toUnmodifiableSet());

    public boolean knows(String eventType) {
        return eventType != null && ALL.contains(eventType);
    }

    public Category categoryOf(String eventType) {
        if (eventType == null) return Category.UNKNOWN;
        if (ORDER_EVENTS.contains(eventType)) return Category.ORDER;
        if (INVENTORY_EVENTS.contains(eventType)) return Category.INVENTORY;
        if (PAYMENT_EVENTS.contains(eventType)) return Category.PAYMENT;
        return Category.UNKNOWN;
    }

    public enum Category {
        ORDER,
        INVENTORY,
        PAYMENT,
        UNKNOWN
    }
}

package com.example.omp.shared.events;

/**
 * The specific types of order events.
 *
 * <p>Values are strings (not a Java enum) because Kafka events cross service boundaries and must be
 * forward-compatible. If a new event type is added, old consumers see the string and can ignore it
 * if unrecognized.
 *
 * <p>Constants are used as the value of {@code OrderEvent.eventType()}.
 */
public final class OrderEventType {

    private OrderEventType() {
        // Constants only.
    }

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_PLACED = "ORDER_PLACED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
}

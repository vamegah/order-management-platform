package com.example.omp.shared.events;

/**
 * Base interface for all order events.
 *
 * <p>Order events are published to the {@code orders.events.order} topic. They're consumed by
 * notification-service (for real-time order updates to subscribed clients) and by any future
 * audit/analytics services.
 *
 * <p>All order events share a set of common fields:
 *
 * <ul>
 *   <li>{@code orderId} — the aggregate ID (also the partition key).
 *   <li>{@code orderNumber} — the human-readable order identifier.
 *   <li>{@code customerId} — the customer the order belongs to.
 *   <li>{@code status} — the current status after the event.
 * </ul>
 */
public interface OrderEvent extends BaseEvent {

    /** The order's UUID. Also the partition key. */
    String orderId();

    /** Human-readable order identifier (e.g., "ORD-2025-0001234"). */
    String orderNumber();

    /** Customer UUID. */
    String customerId();

    /** Current order status after this event. */
    String status();

    @Override
    default String aggregateId() {
        return orderId();
    }
}

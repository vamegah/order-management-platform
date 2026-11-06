package com.example.omp.order.events;

import com.example.omp.order.domain.Order;
import com.example.omp.shared.events.OrderEventType;
import com.example.omp.shared.events.OrderEvents;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds order event records from domain entities.
 *
 * <p>Pure functions: no I/O, no Spring dependencies. Easy to unit test.
 *
 * <p><b>Envelope fields</b> (eventId, occurredAt, schemaVersion) are set here. The {@code eventId}
 * is a new UUID per event — used by consumers for idempotency if they process events more than
 * once.
 */
public final class OrderEventFactory {

    private OrderEventFactory() {
        // Utility class.
    }

    public static OrderEvents.OrderCreated created(Order order) {
        return new OrderEvents.OrderCreated(
                UUID.randomUUID().toString(),
                OrderEventType.ORDER_CREATED,
                Instant.now(),
                OrderEvents.OrderCreated.CURRENT_SCHEMA_VERSION,
                order.getId().toString(),
                order.getOrderNumber().value(),
                order.getCustomerId().toString(),
                order.getStatus().name(),
                order.getCustomerEmail(),
                order.getTotal().getCurrencyCode(),
                order.getSubtotal().getAmount(),
                order.getLines().size());
    }

    public static OrderEvents.OrderPlaced placed(Order order, String placedBy) {
        return new OrderEvents.OrderPlaced(
                UUID.randomUUID().toString(),
                OrderEventType.ORDER_PLACED,
                Instant.now(),
                OrderEvents.OrderPlaced.CURRENT_SCHEMA_VERSION,
                order.getId().toString(),
                order.getOrderNumber().value(),
                order.getCustomerId().toString(),
                order.getStatus().name(),
                placedBy);
    }

    public static OrderEvents.OrderConfirmed confirmed(
            Order order, String confirmedBy, String reason) {
        return new OrderEvents.OrderConfirmed(
                UUID.randomUUID().toString(),
                OrderEventType.ORDER_CONFIRMED,
                Instant.now(),
                OrderEvents.OrderConfirmed.CURRENT_SCHEMA_VERSION,
                order.getId().toString(),
                order.getOrderNumber().value(),
                order.getCustomerId().toString(),
                order.getStatus().name(),
                confirmedBy,
                reason);
    }

    public static OrderEvents.OrderShipped shipped(Order order, String shippedBy, String reason) {
        return new OrderEvents.OrderShipped(
                UUID.randomUUID().toString(),
                OrderEventType.ORDER_SHIPPED,
                Instant.now(),
                OrderEvents.OrderShipped.CURRENT_SCHEMA_VERSION,
                order.getId().toString(),
                order.getOrderNumber().value(),
                order.getCustomerId().toString(),
                order.getStatus().name(),
                shippedBy,
                reason);
    }

    public static OrderEvents.OrderDelivered delivered(
            Order order, String deliveredBy, String reason) {
        return new OrderEvents.OrderDelivered(
                UUID.randomUUID().toString(),
                OrderEventType.ORDER_DELIVERED,
                Instant.now(),
                OrderEvents.OrderDelivered.CURRENT_SCHEMA_VERSION,
                order.getId().toString(),
                order.getOrderNumber().value(),
                order.getCustomerId().toString(),
                order.getStatus().name(),
                deliveredBy,
                reason);
    }

    public static OrderEvents.OrderCancelled cancelled(
            Order order, String cancelledBy, String reason) {
        return new OrderEvents.OrderCancelled(
                UUID.randomUUID().toString(),
                OrderEventType.ORDER_CANCELLED,
                Instant.now(),
                OrderEvents.OrderCancelled.CURRENT_SCHEMA_VERSION,
                order.getId().toString(),
                order.getOrderNumber().value(),
                order.getCustomerId().toString(),
                order.getStatus().name(),
                cancelledBy,
                reason);
    }
}

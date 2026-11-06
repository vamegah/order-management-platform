package com.example.omp.shared.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Concrete order event records.
 *
 * <p>All order events are published to the {@code orders.events.order} topic. Each record is a Java
 * record — immutable, concise, JSON-friendly.
 *
 * <p>Every event shares the envelope fields from {@link OrderEvent}: eventId, eventType,
 * occurredAt, orderId, orderNumber, customerId, status. The record components below only list the
 * domain-specific fields; the envelope fields are declared per record (Java records don't inherit).
 *
 * <p><b>Schema evolution:</b> adding a field is safe. Consumers ignore unknown fields via
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}. Removing or renaming a field requires a new
 * schema version.
 */
public final class OrderEvents {

    private OrderEvents() {
        // Container class — only nested records.
    }

    /** Event emitted when an order is first created (status DRAFT). */
    public record OrderCreated(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String orderId,
            String orderNumber,
            String customerId,
            String status,
            String customerEmail,
            String currency,
            BigDecimal subtotal,
            int lineCount)
            implements OrderEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Event emitted when an order is placed (DRAFT → PLACED). */
    public record OrderPlaced(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String orderId,
            String orderNumber,
            String customerId,
            String status,
            String placedBy)
            implements OrderEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /**
     * Event emitted when an order is confirmed (PLACED → CONFIRMED). Typically follows payment
     * confirmation.
     */
    public record OrderConfirmed(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String orderId,
            String orderNumber,
            String customerId,
            String status,
            String confirmedBy,
            String reason)
            implements OrderEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Event emitted when an order is shipped (CONFIRMED → SHIPPED). */
    public record OrderShipped(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String orderId,
            String orderNumber,
            String customerId,
            String status,
            String shippedBy,
            String reason)
            implements OrderEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Event emitted when an order is delivered (SHIPPED → DELIVERED). */
    public record OrderDelivered(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String orderId,
            String orderNumber,
            String customerId,
            String status,
            String deliveredBy,
            String reason)
            implements OrderEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Event emitted when an order is cancelled (from DRAFT, PLACED, or CONFIRMED). */
    public record OrderCancelled(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String orderId,
            String orderNumber,
            String customerId,
            String status,
            String cancelledBy,
            String reason)
            implements OrderEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }
}

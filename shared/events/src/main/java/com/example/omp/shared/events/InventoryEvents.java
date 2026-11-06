package com.example.omp.shared.events;

import java.time.Instant;

/**
 * Concrete inventory event records.
 *
 * <p>Published to the {@code inventory.events.reservation} topic.
 */
public final class InventoryEvents {

    private InventoryEvents() {}

    /** Emitted when a reservation is created (status ACTIVE). */
    public record ReservationCreated(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String reservationId,
            String sku,
            String warehouseId,
            String orderId,
            int quantity)
            implements InventoryEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a reservation is committed (converted to a permanent stock decrement). */
    public record ReservationCommitted(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String reservationId,
            String sku,
            String warehouseId,
            String orderId,
            int quantity,
            String reason)
            implements InventoryEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a reservation is released (stock returned to available). */
    public record ReservationReleased(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String reservationId,
            String sku,
            String warehouseId,
            String orderId,
            int quantity,
            String reason)
            implements InventoryEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a reservation is expired by the background job. */
    public record ReservationExpired(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String reservationId,
            String sku,
            String warehouseId,
            String orderId,
            int quantity,
            String reason)
            implements InventoryEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }
}

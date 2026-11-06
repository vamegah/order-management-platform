package com.example.omp.shared.events;

/**
 * Base interface for all inventory events.
 *
 * <p>Published to the {@code inventory.events.reservation} topic. Consumed by notification-service
 * for real-time stock and reservation updates.
 *
 * <p>All inventory events share:
 *
 * <ul>
 *   <li>{@code reservationId} — the aggregate ID (also the partition key).
 *   <li>{@code sku} — the SKU affected.
 *   <li>{@code warehouseId} — the warehouse.
 *   <li>{@code orderId} — the order the reservation belongs to.
 * </ul>
 */
public interface InventoryEvent extends BaseEvent {

    /** The reservation UUID. Also the partition key. */
    String reservationId();

    /** The SKU affected by this event. */
    String sku();

    /** The warehouse ID. */
    String warehouseId();

    /** The order the reservation belongs to. */
    String orderId();

    @Override
    default String aggregateId() {
        return reservationId();
    }
}

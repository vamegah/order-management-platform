package com.example.omp.inventory.events;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.shared.events.InventoryEventType;
import com.example.omp.shared.events.InventoryEvents;
import java.time.Instant;
import java.util.UUID;

public final class InventoryEventFactory {

    private InventoryEventFactory() {}

    public static InventoryEvents.ReservationCreated created(Reservation reservation) {
        return new InventoryEvents.ReservationCreated(
                UUID.randomUUID().toString(),
                InventoryEventType.RESERVATION_CREATED,
                Instant.now(),
                InventoryEvents.ReservationCreated.CURRENT_SCHEMA_VERSION,
                reservation.getId().toString(),
                reservation.getSku(),
                reservation.getWarehouseId(),
                reservation.getOrderId().toString(),
                reservation.getQuantity());
    }

    public static InventoryEvents.ReservationCommitted committed(
            Reservation reservation, String reason) {
        return new InventoryEvents.ReservationCommitted(
                UUID.randomUUID().toString(),
                InventoryEventType.RESERVATION_COMMITTED,
                Instant.now(),
                InventoryEvents.ReservationCommitted.CURRENT_SCHEMA_VERSION,
                reservation.getId().toString(),
                reservation.getSku(),
                reservation.getWarehouseId(),
                reservation.getOrderId().toString(),
                reservation.getQuantity(),
                reason);
    }

    public static InventoryEvents.ReservationReleased released(
            Reservation reservation, String reason) {
        return new InventoryEvents.ReservationReleased(
                UUID.randomUUID().toString(),
                InventoryEventType.RESERVATION_RELEASED,
                Instant.now(),
                InventoryEvents.ReservationReleased.CURRENT_SCHEMA_VERSION,
                reservation.getId().toString(),
                reservation.getSku(),
                reservation.getWarehouseId(),
                reservation.getOrderId().toString(),
                reservation.getQuantity(),
                reason);
    }

    public static InventoryEvents.ReservationExpired expired(
            Reservation reservation, String reason) {
        return new InventoryEvents.ReservationExpired(
                UUID.randomUUID().toString(),
                InventoryEventType.RESERVATION_EXPIRED,
                Instant.now(),
                InventoryEvents.ReservationExpired.CURRENT_SCHEMA_VERSION,
                reservation.getId().toString(),
                reservation.getSku(),
                reservation.getWarehouseId(),
                reservation.getOrderId().toString(),
                reservation.getQuantity(),
                reason);
    }
}

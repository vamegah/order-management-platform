package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.dto.ReservationReferenceRepairRequest;
import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.error.*;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Restores missing pointers to existing resources without allocating or mutating inventory. */
@Service
public class OrderReservationRepairService {
    private final OrderReservationRepairStore store;
    private final InventoryClient inventory;

    public OrderReservationRepairService(
            OrderReservationRepairStore store, InventoryClient inventory) {
        this.store = store;
        this.inventory = inventory;
    }

    public ReservationReferenceRepairView inspect(UUID orderId) {
        return store.inspect(orderId);
    }

    public ReservationReferenceRepairView restore(
            UUID orderId, ReservationReferenceRepairRequest request, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 200)
            throw new AccessDeniedException("A verified operator identity is required");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Reservation verification must run outside a database transaction");
        var bindings = OrderReservationRepairStore.bindings(request);
        var before = store.inspect(orderId);
        if (OrderReservationRepairStore.verifyPlan(before, bindings) == 0) return before;
        OrderReservationRepairStore.requireVersion(before, request.expectedVersion());
        for (var line : before.lines()) {
            UUID reservationId = bindings.get(line.lineId());
            ReservationDto reservation;
            try {
                reservation = inventory.get(reservationId);
            } catch (ProductUnavailableException missing) {
                throw new InvalidOrderDataException("The reservation was not found");
            } catch (RuntimeException unavailable) {
                throw new DownstreamServiceException(
                        "inventory-service", 502, "Reservation verification unavailable");
            }
            if (reservation == null || !reservationId.equals(reservation.id()))
                throw new DownstreamServiceException(
                        "inventory-service", 502, "Invalid reservation verification response");
            if (line.reservationWarehouseId() != null
                    && !line.reservationWarehouseId().equals(reservation.warehouseId()))
                throw new InvalidOrderDataException(
                        "The reservation does not match the original warehouse");
            if (reservation.warehouseId() == null
                    || reservation.warehouseId().isBlank()
                    || reservation.warehouseId().length() > 64
                    || !reservation.warehouseId().equals(reservation.warehouseId().strip()))
                throw new DownstreamServiceException(
                        "inventory-service", 502, "Invalid reservation warehouse response");
            if (!orderId.equals(reservation.orderId())
                    || !line.sku().equals(reservation.sku())
                    || line.quantity() != reservation.quantity()
                    || (line.reservationWarehouseId() != null
                            && !line.reservationWarehouseId().equals(reservation.warehouseId())))
                throw new InvalidOrderDataException(
                        "The reservation does not match the order line");
            if (!"RESERVATION_STATUS_ACTIVE".equals(reservation.status()))
                throw new InvalidOrderStateException(
                        "Reference repair requires an active reservation");
        }
        return store.restore(
                orderId, request.expectedVersion(), bindings, actor, request.reason().strip());
    }
}

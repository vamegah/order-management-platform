package com.example.omp.order.clients.grpc;

import com.example.omp.proto.inventory.*;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process inventory fixture for order-service's unit and integration tests.
 *
 * <p>Tracks reservations in memory. Responds to:
 *
 * <ul>
 *   <li>GetStockLevel — returns a fixed stock level.
 *   <li>CheckAvailability — returns true/false based on configured stock.
 *   <li>ReserveStock — creates a reservation, tracks it by ID.
 *   <li>ReleaseReservation — marks a reservation as RELEASED.
 *   <li>CommitReservation — marks a reservation as COMMITTED.
 *   <li>GetReservation — looks up a reservation by ID.
 * </ul>
 *
 * <p>Tests can configure behavior via the public methods (e.g., set stock to zero to test
 * FAILED_PRECONDITION).
 */
public class FakeInventoryService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    private int onHandStock = 100;
    private int reservedStock = 0;

    /** Configure the available stock. */
    public void setOnHandStock(int quantity) {
        this.onHandStock = quantity;
    }

    @Override
    public void getStockLevel(GetStockLevelRequest request, StreamObserver<StockLevel> observer) {
        observer.onNext(
                StockLevel.newBuilder()
                        .setSku(request.getSku())
                        .setWarehouseId(request.getWarehouseId())
                        .setQuantityOnHand(onHandStock)
                        .setQuantityReserved(reservedStock)
                        .setQuantityAvailable(onHandStock - reservedStock)
                        .setLastUpdatedEpochMillis(System.currentTimeMillis())
                        .build());
        observer.onCompleted();
    }

    @Override
    public void checkAvailability(
            CheckAvailabilityRequest request, StreamObserver<CheckAvailabilityResponse> observer) {
        int available = onHandStock - reservedStock;
        observer.onNext(
                CheckAvailabilityResponse.newBuilder()
                        .setAvailable(available >= request.getRequestedQuantity())
                        .setAvailableQuantity(available)
                        .build());
        observer.onCompleted();
    }

    @Override
    public void reserveStock(ReserveStockRequest request, StreamObserver<Reservation> observer) {
        // Check stock availability.
        int available = onHandStock - reservedStock;
        if (available < request.getQuantity()) {
            observer.onError(
                    io.grpc.Status.FAILED_PRECONDITION
                            .withDescription(
                                    "Insufficient stock: requested "
                                            + request.getQuantity()
                                            + ", available "
                                            + available)
                            .asRuntimeException());
            return;
        }

        // Idempotency: return existing reservation for (orderId, sku).
        for (Reservation r : reservations.values()) {
            if (r.getOrderId().equals(request.getOrderId())
                    && r.getSku().equals(request.getSku())
                    && r.getStatus() == ReservationStatus.RESERVATION_STATUS_ACTIVE) {
                observer.onNext(r);
                observer.onCompleted();
                return;
            }
        }

        String id = java.util.UUID.randomUUID().toString();
        Reservation reservation =
                Reservation.newBuilder()
                        .setId(id)
                        .setSku(request.getSku())
                        .setWarehouseId(request.getWarehouseId())
                        .setQuantity(request.getQuantity())
                        .setOrderId(request.getOrderId())
                        .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                        .setCreatedAtEpochMillis(System.currentTimeMillis())
                        .setUpdatedAtEpochMillis(System.currentTimeMillis())
                        .build();

        reservations.put(id, reservation);
        reservedStock += request.getQuantity();

        observer.onNext(reservation);
        observer.onCompleted();
    }

    @Override
    public void releaseReservation(
            ReleaseReservationRequest request, StreamObserver<Reservation> observer) {
        Reservation existing = reservations.get(request.getReservationId());
        if (existing == null) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Reservation not found: " + request.getReservationId())
                            .asRuntimeException());
            return;
        }

        if (existing.getStatus() == ReservationStatus.RESERVATION_STATUS_RELEASED) {
            observer.onNext(existing);
            observer.onCompleted();
            return;
        }
        if (existing.getStatus() != ReservationStatus.RESERVATION_STATUS_ACTIVE) {
            observer.onError(
                    io.grpc.Status.FAILED_PRECONDITION
                            .withDescription("Reservation cannot be released")
                            .asRuntimeException());
            return;
        }

        Reservation updated =
                existing.toBuilder()
                        .setStatus(ReservationStatus.RESERVATION_STATUS_RELEASED)
                        .setUpdatedAtEpochMillis(System.currentTimeMillis())
                        .build();
        reservations.put(request.getReservationId(), updated);
        reservedStock -= existing.getQuantity();

        observer.onNext(updated);
        observer.onCompleted();
    }

    @Override
    public void commitReservation(
            CommitReservationRequest request, StreamObserver<Reservation> observer) {
        Reservation existing = reservations.get(request.getReservationId());
        if (existing == null) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Reservation not found: " + request.getReservationId())
                            .asRuntimeException());
            return;
        }

        if (existing.getStatus() == ReservationStatus.RESERVATION_STATUS_COMMITTED) {
            observer.onNext(existing);
            observer.onCompleted();
            return;
        }
        if (existing.getStatus() != ReservationStatus.RESERVATION_STATUS_ACTIVE) {
            observer.onError(
                    io.grpc.Status.FAILED_PRECONDITION
                            .withDescription("Reservation cannot be committed")
                            .asRuntimeException());
            return;
        }

        Reservation updated =
                existing.toBuilder()
                        .setStatus(ReservationStatus.RESERVATION_STATUS_COMMITTED)
                        .setUpdatedAtEpochMillis(System.currentTimeMillis())
                        .build();
        reservations.put(request.getReservationId(), updated);
        onHandStock -= existing.getQuantity();
        reservedStock -= existing.getQuantity();

        observer.onNext(updated);
        observer.onCompleted();
    }

    @Override
    public void getReservation(
            GetReservationRequest request, StreamObserver<Reservation> observer) {
        Reservation existing = reservations.get(request.getReservationId());
        if (existing == null) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Reservation not found: " + request.getReservationId())
                            .asRuntimeException());
            return;
        }
        observer.onNext(existing);
        observer.onCompleted();
    }
}

package com.example.omp.inventory.grpc.handler;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.domain.ReservationStatus;
import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.error.InventoryNotFoundException;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.grpc.converter.ProtoConverters;
import com.example.omp.inventory.repository.ReservationRepository;
import com.example.omp.inventory.repository.StockLevelRepository;
import com.example.omp.proto.inventory.CommitReservationRequest;
import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.ReleaseReservationRequest;
import com.example.omp.proto.inventory.ReserveStockRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the reservation lifecycle.
 *
 * <p>The critical concurrency concern: two orders reserving the same SKU at the same time. Both
 * read the same StockLevel, both try to increment {@code quantityReserved}, one wins, the other
 * throws {@code OptimisticLockingFailureException} (from JPA's {@code @Version}).
 *
 * <p>The {@code @Retryable} annotation retries the operation up to 3 times with a small backoff. On
 * retry, the second transaction re-reads the (now updated) StockLevel and either succeeds (if stock
 * is still sufficient) or throws {@code InsufficientStockException} (if not).
 *
 * <p><b>Idempotency:</b> {@code reserve} is idempotent on {@code (orderId, sku)}. Calling it twice
 * returns the existing reservation rather than creating a duplicate. This is essential because gRPC
 * clients retry on transient network failures.
 */
@Component
public class ReservationHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationHandler.class);

    private final ReservationRepository reservationRepository;
    private final StockLevelRepository stockLevelRepository;
    private final InventoryEventPublisher eventPublisher;

    public ReservationHandler(
            ReservationRepository reservationRepository,
            StockLevelRepository stockLevelRepository,
            InventoryEventPublisher eventPublisher) {
        this.reservationRepository = reservationRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.eventPublisher = eventPublisher;
    }

    // =========================================================================
    // Reserve
    // =========================================================================

    /**
     * Reserves stock for an order. Idempotent on (orderId, sku).
     *
     * <p>Retries on optimistic lock contention.
     */
    @Transactional
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2.0))
    public com.example.omp.proto.inventory.Reservation reserve(ReserveStockRequest request) {
        UUID orderId = UUID.fromString(request.getOrderId());

        // Idempotency: if a reservation already exists for this (order, sku),
        // return it. The client's retry after a timeout is a no-op.
        var existing = reservationRepository.findByOrderIdAndSku(orderId, request.getSku());
        if (existing.isPresent()) {
            Reservation previous = existing.get();
            if (previous.getQuantity() != request.getQuantity()
                    || !previous.getWarehouseId().equals(request.getWarehouseId())) {
                throw new IllegalArgumentException(
                        "Reservation retry must preserve quantity and warehouse");
            }
            if (previous.getStatus() != ReservationStatus.ACTIVE) {
                throw new IllegalStateException("Reservation is no longer active");
            }
            log.debug(
                    "Reserve is idempotent — returning existing reservation {} for order {} sku {}",
                    existing.get().getId(),
                    orderId,
                    request.getSku());
            return ProtoConverters.toProto(existing.get());
        }

        // Look up the stock level. If not found, the SKU isn't tracked.
        StockLevel stock =
                stockLevelRepository
                        .findBySkuAndWarehouseId(request.getSku(), request.getWarehouseId())
                        .orElseThrow(
                                () ->
                                        new InventoryNotFoundException(
                                                "No stock record for SKU '"
                                                        + request.getSku()
                                                        + "' in warehouse '"
                                                        + request.getWarehouseId()
                                                        + "'"));

        // Reserve on the StockLevel. Throws InsufficientStockException if
        // not enough available.
        stock.reserve(request.getQuantity());

        // Create the Reservation entity and persist both.
        Reservation reservation =
                new Reservation(
                        request.getSku(), request.getWarehouseId(), request.getQuantity(), orderId);
        reservationRepository.save(reservation);
        // Persist the event in the same transaction as the stock hold.
        eventPublisher.publishReservationCreated(reservation);

        // The save on stock is implicit via the managed entity; JPA flushes
        // on transaction commit. If a concurrent transaction updated the
        // version, the commit fails with OptimisticLockingFailureException,
        // and @Retryable re-runs this method.
        log.info(
                "Reserved {}x{} in {} for order {}",
                request.getQuantity(),
                request.getSku(),
                request.getWarehouseId(),
                orderId);

        return ProtoConverters.toProto(reservation);
    }

    // =========================================================================
    // Release
    // =========================================================================

    /**
     * Releases a reservation. Idempotent — releasing an already-RELEASED reservation is a no-op.
     *
     * <p>If the reservation is COMMITTED, throws {@code IllegalStateException} which the advice
     * maps to {@code FAILED_PRECONDITION}.
     */
    @Transactional
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2.0))
    public com.example.omp.proto.inventory.Reservation release(ReleaseReservationRequest request) {
        Reservation reservation = findReservation(request.getReservationId());

        if (reservation.getStatus() == ReservationStatus.RELEASED
                || reservation.getStatus() == ReservationStatus.EXPIRED) {
            // Idempotent: already released.
            return ProtoConverters.toProto(reservation);
        }
        if (reservation.getStatus().isTerminal()) {
            throw new IllegalStateException(
                    "Cannot release reservation in status " + reservation.getStatus());
        }

        // Restore stock.
        StockLevel stock =
                stockLevelRepository
                        .findBySkuAndWarehouseId(reservation.getSku(), reservation.getWarehouseId())
                        .orElseThrow(
                                () ->
                                        new InventoryNotFoundException(
                                                "Stock record missing for reservation "
                                                        + reservation.getId()));
        stock.release(reservation.getQuantity());

        // Transition reservation.
        reservation.release();

        eventPublisher.publishReservationReleased(reservation, request.getReason());

        log.info(
                "Released reservation {} (qty={})", reservation.getId(), reservation.getQuantity());
        return ProtoConverters.toProto(reservation);
    }

    // =========================================================================
    // Commit
    // =========================================================================

    /**
     * Commits a reservation: stock is decremented permanently. Idempotent — committing an
     * already-COMMITTED reservation is a no-op.
     */
    @Transactional
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2.0))
    public com.example.omp.proto.inventory.Reservation commit(CommitReservationRequest request) {
        Reservation reservation = findReservation(request.getReservationId());

        if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            // Idempotent: already committed.
            return ProtoConverters.toProto(reservation);
        }
        if (reservation.getStatus().isTerminal()) {
            throw new IllegalStateException(
                    "Cannot commit reservation in status " + reservation.getStatus());
        }

        StockLevel stock =
                stockLevelRepository
                        .findBySkuAndWarehouseId(reservation.getSku(), reservation.getWarehouseId())
                        .orElseThrow(
                                () ->
                                        new InventoryNotFoundException(
                                                "Stock record missing for reservation "
                                                        + reservation.getId()));
        stock.commit(reservation.getQuantity());

        reservation.commit();

        eventPublisher.publishReservationCommitted(reservation, request.getReason());

        log.info(
                "Committed reservation {} (qty={})",
                reservation.getId(),
                reservation.getQuantity());
        return ProtoConverters.toProto(reservation);
    }

    // =========================================================================
    // Get
    // =========================================================================

    @Transactional(readOnly = true)
    public com.example.omp.proto.inventory.Reservation get(GetReservationRequest request) {
        Reservation reservation = findReservation(request.getReservationId());
        return ProtoConverters.toProto(reservation);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasAuthority('SCOPE_order.reconcile')")
    public com.example.omp.proto.inventory.FindReservationsByOrderResponse findByOrder(
            com.example.omp.proto.inventory.FindReservationsByOrderRequest request) {
        var reservations =
                reservationRepository.findTop101ByOrderIdOrderByCreatedAtAsc(
                        UUID.fromString(request.getOrderId()));
        if (reservations.size() > 100)
            throw new IllegalStateException("Recovery lookup exceeds 100 reservations");
        return com.example.omp.proto.inventory.FindReservationsByOrderResponse.newBuilder()
                .addAllReservations(reservations.stream().map(ProtoConverters::toProto).toList())
                .build();
    }

    private Reservation findReservation(String idString) {
        UUID id;
        try {
            id = UUID.fromString(idString);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid reservation ID format: " + idString, e);
        }
        return reservationRepository
                .findById(id)
                .orElseThrow(() -> new InventoryNotFoundException("Reservation not found: " + id));
    }
}

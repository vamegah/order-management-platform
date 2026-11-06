package com.example.omp.inventory.repository;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link Reservation}. */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Finds a reservation by order and SKU. Used for idempotency: reserving the same (order, SKU)
     * twice returns the existing reservation.
     */
    Optional<Reservation> findByOrderIdAndSku(UUID orderId, String sku);

    /**
     * Finds all reservations for an order. Useful when an order is cancelled and all its
     * reservations must be released.
     */
    List<Reservation> findByOrderId(UUID orderId);

    List<Reservation> findTop101ByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /** Finds all reservations in a given status for an order. */
    List<Reservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    @org.springframework.data.jpa.repository.Query(
            value =
                    "SELECT * FROM inventory.reservation WHERE status = 'ACTIVE' AND created_at < :cutoff ORDER BY created_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Reservation> lockExpiredBatch(
            @org.springframework.data.repository.query.Param("cutoff") Instant cutoff,
            @org.springframework.data.repository.query.Param("batchSize") int batchSize);

    /**
     * Finds ACTIVE reservations older than a cutoff. Used by the expiration job (Phase 7) to clean
     * up stale holds.
     */
    List<Reservation> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant cutoff);
}

package com.example.omp.payment.repository;

import com.example.omp.payment.domain.Refund;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link Refund}. */
@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    interface Candidate {
        UUID getId();

        UUID getPaymentId();
    }

    @Query(
            value =
                    """
        SELECT id, payment_id AS "paymentId" FROM payments.refund
        WHERE status = 'PENDING' AND attention_reason IS NULL
          AND next_attempt_at <= now() AND (attempt_until IS NULL OR attempt_until <= now())
        ORDER BY next_attempt_at, id LIMIT 10
        """,
            nativeQuery = true)
    List<Candidate> findDueRecovery();

    long countByStatus(com.example.omp.payment.domain.RefundStatus status);

    long countByStatusAndAttentionReasonIsNotNull(
            com.example.omp.payment.domain.RefundStatus status);

    /** Finds a refund by idempotency key. */
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    @Query(
            "select r from Refund r where r.payment.id = :paymentId and r.providerReference = :reference")
    Optional<Refund> findByPaymentIdAndProviderReference(
            @Param("paymentId") UUID paymentId, @Param("reference") String providerReference);

    @Query(
            "select count(r) > 0 from Refund r where r.payment.id = :paymentId and r.status = :status")
    boolean existsByPaymentIdAndStatus(
            @Param("paymentId") UUID paymentId,
            @Param("status") com.example.omp.payment.domain.RefundStatus status);

    /** All refunds for a payment. */
    @Query(
            "select r from Refund r where r.payment.id = :paymentId order by r.createdAt asc, r.id asc")
    List<Refund> findByPaymentIdOrderByCreatedAtAsc(@Param("paymentId") UUID paymentId);

    /**
     * Sum of all COMPLETED refund amounts for a payment.
     *
     * <p>This is the authoritative calculation of refunded amount. The denormalized {@code
     * Payment.refundedAmount} field exists for fast reads; this query is used to reconcile if
     * there's ever a discrepancy.
     */
    @Query(
            """
        SELECT COALESCE(SUM(r.amount.amount), 0)
        FROM Refund r
        WHERE r.payment.id = :paymentId
          AND r.status = 'COMPLETED'
        """)
    BigDecimal sumCompletedRefunds(@Param("paymentId") UUID paymentId);
}

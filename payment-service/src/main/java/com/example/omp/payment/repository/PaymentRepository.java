package com.example.omp.payment.repository;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Repository for {@link Payment}. */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Serializes refund admission and completion; never held during provider I/O. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockForRefund(@org.springframework.data.repository.query.Param("id") UUID id);

    /** Recovery skips busy payments rather than waiting behind a request or webhook. */
    @Query(
            value = "SELECT * FROM payments.payment WHERE id = :id FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Payment> tryLockForRefund(
            @org.springframework.data.repository.query.Param("id") UUID id);

    /**
     * Finds a payment by idempotency key. Used by the idempotency check in {@code
     * CreatePaymentIntent}.
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** Finds all payments for an order. */
    List<Payment> findByOrderId(UUID orderId);

    List<Payment> findTop101ByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /** Finds payments for a customer, most recent first. */
    List<Payment> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /** Finds payments by status. */
    List<Payment> findByStatus(PaymentStatus status);

    /** Finds by provider reference. Useful for webhook handling (Phase 5). */
    Optional<Payment> findByProviderReference(String providerReference);

    Optional<Payment> findByProviderAndProviderReference(String provider, String providerReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select p from Payment p where p.provider = :provider and p.providerReference = :reference")
    Optional<Payment> lockByProviderReference(
            @org.springframework.data.repository.query.Param("provider") String provider,
            @org.springframework.data.repository.query.Param("reference") String reference);

    /** Eager-loads refunds. Used when returning a full Payment with its history. */
    @EntityGraph(attributePaths = {"refunds"})
    Optional<Payment> findWithRefundsById(UUID id);
}

package com.example.omp.payment.domain;

import com.example.omp.shared.domain.Money;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A refund applied to a {@link Payment}.
 *
 * <p>Multiple refunds may be applied to one payment (partial refunds). The sum of refund amounts
 * moves the payment from CONFIRMED to PARTIALLY_REFUNDED, and eventually to REFUNDED.
 *
 * <p><b>Idempotency:</b> {@link #idempotencyKey} is unique when present. Refund requests with the
 * same key return the existing refund.
 */
@Entity
@Table(
        name = "refund",
        schema = "payments",
        indexes = {@Index(name = "idx_refund_payment", columnList = "payment_id")})
@EntityListeners(AuditingEntityListener.class)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column = @Column(name = "amount", nullable = false, precision = 19, scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)"))
    })
    private Money amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Size(max = 500)
    @Column(name = "reason", length = 500)
    private String reason;

    @Size(max = 255)
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "attempt_until")
    private Instant attemptUntil;

    @Column(name = "provider_key", length = 255)
    private String providerKey;

    @Column(name = "recovery_attempts", nullable = false)
    private int recoveryAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 64)
    private String lastError;

    @Column(name = "attention_reason", length = 64)
    private String attentionReason;

    public enum RecoveryBlock {
        LEGACY_INTENT,
        REPLAY_WINDOW_EXPIRED,
        PROVIDER_MISMATCH,
        PAYMENT_STATE_MISMATCH
    }

    public int getRecoveryAttempts() {
        return recoveryAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getAttentionReason() {
        return attentionReason;
    }

    public boolean isDue(Instant now) {
        return !nextAttemptAt.isAfter(now);
    }

    public void requireAttention(RecoveryBlock reason) {
        attentionReason = reason.name();
    }

    public void clearAttention() {
        attentionReason = null;
    }

    public void recordOutcome(boolean uncertain, Instant now) {
        lastError = uncertain ? "PROVIDER_UNRESOLVED" : null;
        nextAttemptAt = now.plusSeconds(Math.min(900, 30L << Math.min(recoveryAttempts, 5)));
        if (status != RefundStatus.PENDING) {
            attentionReason = null;
            if (completedAt == null) completedAt = now;
        }
    }

    public void initializeProviderKey() {
        if (id == null || providerKey != null)
            throw new IllegalStateException("Refund key already bound or ID missing");
        providerKey = "omp-refund-" + id;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public UUID claimAttempt(Instant now) {
        if (status != RefundStatus.PENDING || (attemptUntil != null && attemptUntil.isAfter(now)))
            return null;
        attemptId = UUID.randomUUID();
        attemptUntil = now.plusSeconds(120);
        recoveryAttempts = Math.min(Integer.MAX_VALUE - 1, recoveryAttempts) + 1;
        nextAttemptAt = attemptUntil;
        return attemptId;
    }

    public boolean ownsAttempt(UUID lease) {
        return status == RefundStatus.PENDING && lease != null && lease.equals(attemptId);
    }

    public void releaseAttempt() {
        attemptId = null;
        attemptUntil = null;
    }

    public UUID getPaymentId() {
        return payment.getId();
    }

    public long getAmountMinor() {
        return MinorUnits.fromMoney(amount);
    }

    public String getCurrency() {
        return amount.getCurrencyCode();
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markSucceeded(String reference) {
        markCompleted();
        providerReference = reference;
    }

    public void correlate(String reference) {
        if (reference == null
                || reference.isBlank()
                || reference.length() > 255
                || (providerReference != null && !providerReference.equals(reference)))
            throw new IllegalArgumentException("Invalid provider refund correlation");
        providerReference = reference;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected Refund() {}

    public Refund(Payment payment, Money amount, String reason, String idempotencyKey) {
        this.payment = payment;
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.status = RefundStatus.PENDING;
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    public void markCompleted() {
        if (this.status != RefundStatus.PENDING) {
            throw new IllegalStateException("Cannot complete refund in status " + this.status);
        }
        this.status = RefundStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        if (this.status != RefundStatus.PENDING) {
            throw new IllegalStateException("Cannot fail refund in status " + this.status);
        }
        this.status = RefundStatus.FAILED;
        if (reason != null) {
            this.reason = reason;
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public Money getAmount() {
        return amount;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Refund other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Refund{id=" + id + ", amount=" + amount + ", status=" + status + "}";
    }
}

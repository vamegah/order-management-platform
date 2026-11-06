package com.example.omp.payment.domain;

import com.example.omp.payment.error.PaymentStateException;
import com.example.omp.shared.domain.Money;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A payment intent and its lifecycle.
 *
 * <p>Created in status {@link PaymentStatus#PENDING} with an amount. Confirmed by a call from
 * order-service (or a webhook handler, in Phase 5) after the external payment provider succeeds.
 * Can be cancelled before confirmation, or refunded (fully or partially) after.
 *
 * <p><b>State transitions:</b> every transition is a method ({@link #confirm}, {@link #cancel},
 * {@link #markFailed}, {@link #applyRefund}). Each checks the current status and throws {@link
 * PaymentStateException} if the transition is illegal.
 *
 * <p><b>Refund tracking:</b> {@link #refundedAmount} is denormalized from the sum of {@link Refund}
 * rows. Kept in sync by the service layer inside the refund transaction. Denormalization exists for
 * fast reads (dashboard queries) — the sum of {@code Refund.amount} remains authoritative.
 *
 * <p><b>Idempotency:</b> {@link #idempotencyKey} is unique when present. Retries of {@code
 * CreatePaymentIntent} with the same key return the existing payment rather than creating a
 * duplicate.
 */
@Entity
@Table(
        name = "payment",
        schema = "payments",
        indexes = {
            @Index(name = "idx_payment_order", columnList = "order_id"),
            @Index(name = "idx_payment_customer", columnList = "customer_id"),
            @Index(name = "idx_payment_status_created", columnList = "status,created_at")
        })
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @NotNull
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /**
     * The payment amount. Stored as two columns: amount numeric(19,4) currency char(3) via
     * JPA @AttributeOverride on the embedded Money value object. PostgreSQL reports CHAR as bpchar
     * through JDBC, so use that SQL type name for Hibernate schema validation.
     */
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

    /**
     * Sum of all refunds applied to this payment. Denormalized. Authoritative value is
     * SUM(Refund.amount).
     *
     * <p>Null until the first refund; represents 0 in that case.
     */
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column = @Column(name = "refunded_amount", precision = 19, scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "refunded_currency",
                                length = 3,
                                columnDefinition = "bpchar(3)"))
    })
    private Money refundedAmount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.PENDING;

    /**
     * External provider's reference for this payment (e.g., Stripe's {@code pi_...} id). Set on
     * confirmation. Null while PENDING or after cancellation.
     */
    @Size(max = 255)
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** Provider name (e.g., "stripe", "adyen"). Kept from the original model. */
    @Size(max = 30)
    @Column(name = "provider", length = 30)
    private String provider;

    /**
     * Idempotency key, if provided by the caller. Unique when present. Used to dedupe
     * CreatePaymentIntent requests.
     */
    @Size(max = 255)
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    /** All refunds issued against this payment. */
    @OneToMany(mappedBy = "payment", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<Refund> refunds = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected Payment() {
        // JPA only
    }

    public Payment(UUID orderId, UUID customerId, Money amount) {
        this(orderId, customerId, amount, null);
    }

    public Payment(UUID orderId, UUID customerId, Money amount, String idempotencyKey) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * Confirms the payment. Called after the external provider successfully charges the customer.
     *
     * @param providerReference the provider's identifier (e.g., Stripe `pi_...`)
     * @throws PaymentStateException if the current status is not PENDING
     */
    public void confirm(String providerReference) {
        expect(PaymentStatus.PENDING);
        if (providerReference == null || providerReference.isBlank()) {
            throw new IllegalArgumentException("Provider reference is required to confirm");
        }
        this.status = PaymentStatus.CONFIRMED;
        this.providerReference = providerReference;
        this.authorizedAt = Instant.now();
    }

    /**
     * Alias for {@link #confirm(String)} kept for callers that used the authorize/capture model.
     * Records the authorization timestamp.
     */
    public void markAuthorized(String providerReference) {
        confirm(providerReference);
    }

    /**
     * Marks the payment as captured. In the simplified lifecycle, capture happens implicitly on
     * confirmation, so this method only records the timestamp.
     *
     * @throws PaymentStateException if the payment has not been confirmed
     */
    public void markCaptured() {
        if (this.status != PaymentStatus.CONFIRMED) {
            throw new PaymentStateException(
                    "Cannot capture payment in status " + this.status + " (expected CONFIRMED)");
        }
        this.capturedAt = Instant.now();
    }

    /**
     * Cancels the payment before confirmation.
     *
     * @throws PaymentStateException if the current status is not PENDING
     */
    public void cancel(String reason) {
        expect(PaymentStatus.PENDING);
        this.status = PaymentStatus.CANCELLED;
    }

    /**
     * Marks the payment as failed (external provider rejected or errored).
     *
     * @throws PaymentStateException if the payment has already been captured or refunded
     */
    public void markFailed(String code, String message) {
        expect(PaymentStatus.PENDING);
        this.status = PaymentStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = truncate(message, 500);
        this.failedAt = Instant.now();
    }

    /** Convenience overload for callers that only have a reason string. */
    public void markFailed(String reason) {
        markFailed(null, reason);
    }

    /**
     * Marks the payment as refunded. Kept for parity with the original model; prefer {@link
     * #applyRefund(Money)} for partial refunds.
     *
     * @throws PaymentStateException if the payment is not confirmed
     */
    public void markRefunded() {
        expect(PaymentStatus.CONFIRMED);
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = Instant.now();
    }

    /**
     * Applies a refund. Called by the refund handler inside the refund transaction. Updates the
     * denormalized {@code refundedAmount} and transitions the status accordingly.
     *
     * @param refundAmount the amount being refunded (positive, same currency)
     * @throws PaymentStateException if the payment cannot be refunded
     * @throws IllegalArgumentException if the refund exceeds the remaining balance
     */
    public void applyRefund(Money refundAmount) {
        if (refundAmount == null || refundAmount.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (!status.isRefundable()) {
            throw new PaymentStateException("Cannot refund payment in status " + this.status);
        }
        if (!refundAmount.getCurrencyCode().equals(this.amount.getCurrencyCode())) {
            throw new IllegalArgumentException(
                    "Refund currency "
                            + refundAmount.getCurrencyCode()
                            + " doesn't match payment currency "
                            + this.amount.getCurrencyCode());
        }

        Money currentRefunded =
                this.refundedAmount != null
                        ? this.refundedAmount
                        : Money.of(BigDecimal.ZERO, refundAmount.getCurrency());

        Money newTotal = currentRefunded.add(refundAmount);

        if (newTotal.getAmount().compareTo(this.amount.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Refund of "
                            + refundAmount
                            + " exceeds remaining balance of "
                            + this.amount.subtract(currentRefunded));
        }

        this.refundedAmount = newTotal;

        // Determine new status.
        if (newTotal.getAmount().compareTo(this.amount.getAmount()) == 0) {
            this.status = PaymentStatus.REFUNDED;
            this.refundedAt = Instant.now();
        } else {
            this.status = PaymentStatus.PARTIALLY_REFUNDED;
        }
    }

    private void expect(PaymentStatus expected) {
        if (this.status != expected) {
            throw new PaymentStateException(
                    "Expected payment in " + expected + " but was " + this.status);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // -------------------------------------------------------------------------
    // Derived properties
    // -------------------------------------------------------------------------

    /** Remaining refundable amount = amount - refundedAmount. */
    public Money getRemainingRefundable() {
        Money refunded =
                this.refundedAmount != null
                        ? this.refundedAmount
                        : Money.of(BigDecimal.ZERO, this.amount.getCurrency());
        return this.amount.subtract(refunded);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Money getAmount() {
        return amount;
    }

    public long getAmountMinor() {
        return MinorUnits.fromMoney(amount);
    }

    public String getCurrency() {
        return amount.getCurrencyCode();
    }

    public void setProviderReference(String reference) {
        expect(PaymentStatus.PENDING);
        this.providerReference = reference;
    }

    public Money getRefundedAmount() {
        return refundedAmount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public List<Refund> getRefunds() {
        return refunds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public void setAuthorizedAt(Instant authorizedAt) {
        this.authorizedAt = authorizedAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(Instant refundedAt) {
        this.refundedAt = refundedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Payment{id="
                + id
                + ", orderId="
                + orderId
                + ", amount="
                + amount
                + ", status="
                + status
                + "}";
    }
}

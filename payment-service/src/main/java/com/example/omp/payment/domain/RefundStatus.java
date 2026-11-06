package com.example.omp.payment.domain;

/**
 * Lifecycle of a refund.
 *
 * <p>Refunds are simpler than payments: they're either in flight or done. No
 * partial-refund-of-a-refund concept.
 */
public enum RefundStatus {

    /** Refund requested; awaiting provider confirmation. */
    PENDING,

    /** Refund completed by the provider. Terminal. */
    COMPLETED,

    /** Refund failed. Terminal. */
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}

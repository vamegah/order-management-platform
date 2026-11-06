package com.example.omp.payment.domain;

/**
 * Lifecycle state of a payment.
 *
 * <p>State machine:
 *
 * <pre>
 *              ┌──────► CANCELLED          (terminal)
 *              │
 *   PENDING ───┼──────► FAILED             (terminal)
 *              │
 *              └──────► CONFIRMED ────┬───► REFUNDED             (terminal)
 *                                    │
 *                                    └───► PARTIALLY_REFUNDED   (may transition again)
 * </pre>
 *
 * <p>Values must match the {@code chk_payment_status} check constraint in V1__initial_schema.sql.
 */
public enum PaymentStatus {

    /** Created but not yet confirmed with the external provider. */
    PENDING,

    /** Confirmed. Money has been captured (or is being captured). */
    CONFIRMED,

    /** Cancelled before confirmation. No money was moved. Terminal. */
    CANCELLED,

    /** Failed during external provider interaction. Terminal. */
    FAILED,

    /** Fully refunded. Terminal. */
    REFUNDED,

    /**
     * Partially refunded. Not terminal — additional refunds can move the payment toward REFUNDED.
     * Once the sum of refunds equals the original amount, the status becomes REFUNDED.
     */
    PARTIALLY_REFUNDED;

    /** True if no further state transitions are allowed. */
    public boolean isTerminal() {
        return this == CANCELLED || this == FAILED || this == REFUNDED;
    }

    /** True if the payment can be refunded (fully or partially). */
    public boolean isRefundable() {
        return this == CONFIRMED || this == PARTIALLY_REFUNDED;
    }
}

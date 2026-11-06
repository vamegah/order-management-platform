package com.example.omp.payment.error;

/**
 * Thrown when an illegal state transition is attempted (e.g., confirming a payment that's already
 * been cancelled).
 *
 * <p>Translated to gRPC {@code FAILED_PRECONDITION}.
 */
public class PaymentStateException extends RuntimeException {
    public PaymentStateException(String message) {
        super(message);
    }

    public PaymentStateException(String message, Throwable cause) {
        super(message, cause);
    }
}

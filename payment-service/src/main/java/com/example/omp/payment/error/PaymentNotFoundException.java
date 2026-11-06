package com.example.omp.payment.error;

/**
 * Thrown when a requested payment or refund doesn't exist. Translated to gRPC {@code NOT_FOUND} by
 * {@link com.example.omp.payment.grpc.advice.GrpcExceptionAdvice}.
 */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String message) {
        super(message);
    }

    public PaymentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

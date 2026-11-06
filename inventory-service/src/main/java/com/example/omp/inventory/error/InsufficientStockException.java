package com.example.omp.inventory.error;

/**
 * Thrown when a reservation would exceed available stock.
 *
 * <p>Translated to gRPC status {@code FAILED_PRECONDITION} by {@code GrpcExceptionAdvice}. Clients
 * interpret this as "the request was well-formed, but the current state doesn't allow it" —
 * typically by reducing the requested quantity or failing the order.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }

    public InsufficientStockException(String message, Throwable cause) {
        super(message, cause);
    }
}

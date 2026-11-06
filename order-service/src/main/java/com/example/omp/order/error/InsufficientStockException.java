package com.example.omp.order.error;

/**
 * Thrown when inventory-service reports insufficient stock for a reservation.
 *
 * <p>Distinct from inventory-service's own {@code InsufficientStockException} (which lives in a
 * different package). Order-service has its own, so its domain code doesn't depend on
 * inventory-service's internals.
 *
 * <p>Translated to HTTP 409 (or 400 — implementation choice) by the global exception handler.
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }

    public InsufficientStockException(String message, Throwable cause) {
        super(message, cause);
    }
}

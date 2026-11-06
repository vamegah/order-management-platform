package com.example.omp.inventory.error;

/**
 * Thrown when a requested SKU, reservation, or stock level doesn't exist.
 *
 * <p>Translated to gRPC status {@code NOT_FOUND}.
 */
public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(String message) {
        super(message);
    }

    public InventoryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

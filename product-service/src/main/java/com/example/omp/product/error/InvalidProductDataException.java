package com.example.omp.product.error;

/**
 * Thrown when product data violates a business rule that isn't covered by bean validation (e.g.,
 * cross-field constraints, format rules requiring external lookup).
 *
 * <p>Translated to HTTP 400 by the global exception handler.
 */
public class InvalidProductDataException extends RuntimeException {

    public InvalidProductDataException(String message) {
        super(message);
    }

    public InvalidProductDataException(String message, Throwable cause) {
        super(message, cause);
    }
}

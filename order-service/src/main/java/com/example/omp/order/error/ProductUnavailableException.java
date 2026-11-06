package com.example.omp.order.error;

/**
 * Thrown when a requested product doesn't exist or is unavailable. Translated to HTTP 400 by the
 * global exception handler.
 */
public class ProductUnavailableException extends RuntimeException {

    public ProductUnavailableException(String message) {
        super(message);
    }
}

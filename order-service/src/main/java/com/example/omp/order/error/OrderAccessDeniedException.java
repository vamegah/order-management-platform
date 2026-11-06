package com.example.omp.order.error;

/** Thrown when a user tries to access an order they don't own. Translated to HTTP 403. */
public class OrderAccessDeniedException extends RuntimeException {
    public OrderAccessDeniedException(String message) {
        super(message);
    }
}

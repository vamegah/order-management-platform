package com.example.omp.order.error;

/**
 * Thrown when a state transition isn't allowed by the order state machine. Translated to HTTP 409
 * by the global exception handler (conflict with current state).
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}

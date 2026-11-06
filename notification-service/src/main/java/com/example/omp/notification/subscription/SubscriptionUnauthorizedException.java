package com.example.omp.notification.subscription;

/**
 * Thrown when a client attempts to subscribe to a resource it doesn't have permission to access.
 *
 * <p>Translated to a STOMP ERROR frame by the exception handler.
 */
public class SubscriptionUnauthorizedException extends RuntimeException {
    public SubscriptionUnauthorizedException(String message) {
        super(message);
    }

    public SubscriptionUnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}

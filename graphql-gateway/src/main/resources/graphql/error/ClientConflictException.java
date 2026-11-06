package com.example.omp.gateway.error;

/**
 * Thrown when a downstream service reports a uniqueness or state conflict
 * (HTTP 409).
 *
 * <p>Translated to a GraphQL error with code {@code CONFLICT}.
 *
 * <p>Typical causes:
 * <ul>
 *   <li>Attempting to create a customer with an existing email.</li>
 *   <li>Attempting to create a product with an existing SKU.</li>
 *   <li>Attempting an illegal order state transition.</li>
 * </ul>
 */
public class ClientConflictException extends RuntimeException {

    public ClientConflictException(String message) {
        super(message);
    }

    public ClientConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
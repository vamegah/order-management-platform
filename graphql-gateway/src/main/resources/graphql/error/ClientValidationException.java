package com.example.omp.gateway.error;

/**
 * Thrown when a downstream service rejects the request as invalid
 * (HTTP 400 or 422).
 *
 * <p>Translated to a GraphQL error with code {@code VALIDATION_FAILED}.
 *
 * <p>The message includes the downstream error details, which the client
 * can display or log. This is a client-fault error — the request itself
 * was malformed or violated a business rule.
 */
public class ClientValidationException extends RuntimeException {

    public ClientValidationException(String message) {
        super(message);
    }

    public ClientValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

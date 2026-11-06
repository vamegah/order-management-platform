package com.example.omp.gateway.error;

/**
 * Thrown when a downstream service returns 404 for a requested resource.
 *
 * <p>Translated to a GraphQL error with code {@code NOT_FOUND} by
 * {@link com.example.omp.gateway.graphql.error.GraphQlExceptionResolver}.
 *
 * <p>The message includes the resource identifier to make client-side
 * debugging easier.
 */
public class ClientNotFoundException extends RuntimeException {

    public ClientNotFoundException(String message) {
        super(message);
    }

    public ClientNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

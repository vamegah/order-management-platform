package com.example.omp.order.error;

/**
 * Thrown when a downstream gRPC service rejects the caller's token (UNAUTHENTICATED or
 * PERMISSION_DENIED).
 *
 * <p>Distinct from {@code OrderAccessDeniedException} (which is about the current user's permission
 * on the current order). This exception indicates a token propagation problem.
 *
 * <p>Translated to HTTP 502 by order-service's global exception handler — the client's request was
 * fine; the platform's own auth chain broke.
 */
public class DownstreamAuthenticationException extends RuntimeException {
    public DownstreamAuthenticationException(String message) {
        super(message);
    }

    public DownstreamAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}

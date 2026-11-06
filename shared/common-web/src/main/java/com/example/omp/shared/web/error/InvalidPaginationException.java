package com.example.omp.shared.web.error;

/**
 * Thrown by {@link com.example.omp.shared.web.pagination.PageableResolver} when pagination
 * parameters are invalid (negative page, oversized page, disallowed sort field).
 *
 * <p>Service-specific GlobalExceptionHandlers should map this to HTTP 400 with the message
 * included.
 */
public class InvalidPaginationException extends RuntimeException {

    public InvalidPaginationException(String message) {
        super(message);
    }
}

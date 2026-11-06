package com.example.omp.payment.error;

/**
 * Thrown when an idempotency key is used in a way that conflicts with an existing record.
 *
 * <p>In most cases, the service handles idempotency-key collisions transparently (returns the
 * existing record). This exception is thrown only when the collision isn't resolvable (e.g., a race
 * where two transactions both tried to insert the same key, and the retry logic can't determine
 * which one won).
 *
 * <p>Translated to gRPC {@code ALREADY_EXISTS}.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }

    public DuplicateIdempotencyKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}

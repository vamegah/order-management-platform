package com.example.omp.shared.events.outbox;

/**
 * Thrown when an outbox operation fails in a way the caller must handle.
 *
 * <p>Typically wraps a serialization failure in {@link OutboxWriter} or a repository failure during
 * a write.
 *
 * <p>Drainer-internal failures (Kafka send failures) don't throw this exception; they're recorded
 * on the row itself via {@link OutboxEvent#recordAttemptFailure(String, int)}.
 */
public class OutboxException extends RuntimeException {

    public OutboxException(String message) {
        super(message);
    }

    public OutboxException(String message, Throwable cause) {
        super(message, cause);
    }
}

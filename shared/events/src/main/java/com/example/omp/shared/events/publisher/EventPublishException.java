package com.example.omp.shared.events.publisher;

/**
 * Thrown when an event cannot be published to Kafka.
 *
 * <p>Callers should either:
 *
 * <ul>
 *   <li>Catch and log (best-effort publishing), or
 *   <li>Throw further, causing the surrounding operation to fail.
 * </ul>
 *
 * <p>In Phase 4, publishers are invoked from {@code afterCommit} hooks — the transaction has
 * already committed. Throwing from there just logs; the state change is already persisted.
 *
 * <p>Durable producers use the shared transactional outbox instead; a failure in an after-commit
 * callback cannot roll back the original state change.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}

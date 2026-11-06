package com.example.omp.shared.events.publisher;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Utility for scheduling event publication after transaction commit.
 *
 * <p><b>Why this class exists:</b> publishing events inside a {@code @Transactional} method risks
 * phantom events if the transaction rolls back. The event should only be published after the state
 * change is durable.
 *
 * <p>Spring's {@link TransactionSynchronizationManager} allows registering callbacks that run after
 * the transaction's outcome is known. This class wraps that API for convenience.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @Transactional
 * public Order save(Order order) {
 *     Order saved = repository.save(order);
 *     scheduler.afterCommit(() -> publisher.publishCreated(saved));
 *     return saved;
 * }
 * }</pre>
 *
 * <p><b>Outside a transaction:</b> the callback runs immediately. This is convenient for tests and
 * non-transactional paths but means the event is published synchronously with the caller.
 */
@Component
public class TransactionEventScheduler {

    public void afterCommit(Runnable callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            callback.run();
                        }
                    });
        } else {
            // No active transaction — run immediately.
            callback.run();
        }
    }
}

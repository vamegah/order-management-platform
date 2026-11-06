package com.example.omp.shared.events.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox table for PENDING events and publishes them to Kafka.
 *
 * <p><b>Concurrency:</b> multiple drainers (in multiple instances of the same service) can run
 * concurrently. The repository's {@code SELECT ... FOR UPDATE SKIP LOCKED} guarantees disjoint
 * batches.
 *
 * <p><b>Transaction semantics:</b> the entire drain cycle (claim + publish + mark published) is one
 * transaction. If the process crashes after Kafka publish but before commit, the row stays PENDING
 * and the next drainer re-publishes. Consumers must be idempotent (they already are).
 *
 * <p><b>Failure handling:</b> a publish failure increments the row's attempt counter. After {@code
 * omp.outbox.max-attempts} (default 10) failures, the row transitions to FAILED and requires
 * operator intervention.
 *
 * <p><b>Activation:</b> controlled by {@code omp.outbox.enabled}. Set to {@code false} in tests to
 * disable background publishing.
 */
@Component
@ConditionalOnProperty(name = "omp.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);

    private final OutboxRepository repository;
    private final OutboxDrainerProperties properties;
    private final OutboxMetrics metrics;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxDrainer(
            OutboxRepository repository,
            OutboxDrainerProperties properties,
            OutboxMetrics metrics,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Main drain cycle. Runs on the configured interval. Claims a batch of PENDING rows, publishes
     * each to its topic, marks published on success, records failure on error.
     */
    @Scheduled(
            fixedDelayString =
                    "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${omp.outbox.poll-interval:1s}').toMillis()}")
    @Transactional
    public void drain() {
        if (!properties.isEnabled()) return;
        List<OutboxEvent> batch = repository.claimPendingBatch(properties.getBatchSize());

        if (batch.isEmpty()) {
            return; // Quiet; normal.
        }

        log.debug("Outbox drainer claimed {} rows", batch.size());

        for (OutboxEvent event : batch) {
            publish(event);
            if (Thread.currentThread().isInterrupted()) break;
        }
    }

    /**
     * Publishes a single event. On success, marks the row as published. On failure, increments the
     * attempt counter and either leaves the row PENDING (for retry) or transitions to FAILED.
     */
    private void publish(OutboxEvent event) {
        try {
            // Synchronous send (get()) so we know whether it succeeded before
            // committing the transaction that marks the row as PUBLISHED.
            //
            // If Kafka is slow, this blocks the drainer thread. Batch sizes are
            // small (default 100) so the impact is bounded. A production
            // enhancement would use async sends + a callback that commits on
            // success; that's more complex and defers to Phase 7.
            kafkaTemplate
                    .send(event.getTopic(), event.getAggregateId(), event.getPayload())
                    .get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);

            event.markPublished();
            metrics.incrementPublished();

            log.info(
                    "Outbox published: id={} eventType={} topic={} aggregateId={}",
                    event.getId(),
                    event.getEventType(),
                    event.getTopic(),
                    event.getAggregateId());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            metrics.incrementError();

            event.recordAttemptFailure(
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    properties.getMaxAttempts());

            log.warn(
                    "Outbox publish failed: id={} eventType={} attempts={} error={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAttempts(),
                    e.getMessage());
        }
    }

    /**
     * Deletes PUBLISHED rows older than the retention window.
     *
     * <p>Runs on its own schedule. FAILED and PENDING rows are never deleted.
     */
    @Scheduled(
            fixedDelayString =
                    "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${omp.outbox.cleanup-interval:1h}').toMillis()}")
    @Transactional
    public void cleanup() {
        if (!properties.isEnabled()) return;
        Instant cutoff = Instant.now().minus(properties.getPublishedRetention());
        int deleted = repository.deletePublishedOlderThan(cutoff);

        if (deleted > 0) {
            log.info("Outbox cleanup: deleted {} rows published before {}", deleted, cutoff);
        }
    }
}

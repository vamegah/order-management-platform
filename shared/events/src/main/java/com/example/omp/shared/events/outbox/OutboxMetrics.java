package com.example.omp.shared.events.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the outbox.
 *
 * <p>Exposes:
 *
 * <ul>
 *   <li>{@code outbox.pending} — gauge, current PENDING + FAILED rows.
 *   <li>{@code outbox.failed} — gauge, current FAILED rows.
 *   <li>{@code outbox.published.total} — counter, cumulative successful publishes.
 *   <li>{@code outbox.drainer.errors.total} — counter, cumulative drainer failures.
 * </ul>
 *
 * <p>These appear on {@code /actuator/prometheus} and are used by Grafana dashboards to alert on
 * outbox backlog and drainer health.
 */
@Component
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final OutboxRepository repository;

    private Counter publishedCounter;
    private Counter drainerErrorCounter;

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @PostConstruct
    void register() {
        Gauge.builder("outbox.pending", repository, OutboxRepository::countUnpublished)
                .description("Current number of PENDING + FAILED outbox rows")
                .register(registry);

        Gauge.builder("outbox.failed", repository, OutboxRepository::countFailed)
                .description("Current number of FAILED outbox rows")
                .register(registry);

        publishedCounter =
                Counter.builder("outbox.published.total")
                        .description("Total outbox events successfully published to Kafka")
                        .register(registry);

        drainerErrorCounter =
                Counter.builder("outbox.drainer.errors.total")
                        .description("Total outbox drainer publish errors")
                        .register(registry);
    }

    public void incrementPublished() {
        if (publishedCounter != null) {
            publishedCounter.increment();
        }
    }

    public void incrementError() {
        if (drainerErrorCounter != null) {
            drainerErrorCounter.increment();
        }
    }
}

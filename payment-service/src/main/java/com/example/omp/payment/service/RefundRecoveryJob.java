package com.example.omp.payment.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Owns a dedicated thread so a slow provider does not block the outbox scheduler. */
@Component
@ConditionalOnProperty(name = "payment.refund.recovery.enabled", havingValue = "true")
public class RefundRecoveryJob implements AutoCloseable {
    private final RefundRecoveryService recovery;
    private final MeterRegistry metrics;
    private final long intervalMillis;
    private ScheduledExecutorService executor;

    public RefundRecoveryJob(
            RefundRecoveryService recovery,
            MeterRegistry metrics,
            @Value("${payment.refund.recovery.interval-ms:30000}") long intervalMillis) {
        if (intervalMillis < 1000 || intervalMillis > 3600000)
            throw new IllegalArgumentException("Refund recovery interval must be 1000..3600000 ms");
        this.recovery = recovery;
        this.metrics = metrics;
        this.intervalMillis = intervalMillis;
    }

    @PostConstruct
    public synchronized void start() {
        if (executor != null) return;
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("refund-recovery-", 0).daemon(true).factory());
        executor.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void tick() {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            recovery.retryDue();
        } catch (RuntimeException failure) {
            metrics.counter("payment.refund.recovery.worker.failures").increment();
            LoggerFactory.getLogger(getClass())
                    .warn("Refund recovery tick failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

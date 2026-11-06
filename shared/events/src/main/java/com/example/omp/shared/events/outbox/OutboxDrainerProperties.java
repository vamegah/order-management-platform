package com.example.omp.shared.events.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the outbox drainer.
 *
 * <p>Bound to {@code omp.outbox.*} in application.yml. Sensible defaults for dev; production may
 * tune for higher throughput.
 */
@ConfigurationProperties(prefix = "omp.outbox")
public class OutboxDrainerProperties {

    /**
     * Whether the drainer runs. Set to {@code false} in tests that don't want background Kafka
     * publishing.
     */
    private boolean enabled = true;

    /**
     * How often to poll the outbox for PENDING rows. Shorter = lower latency between state change
     * and Kafka publish; longer = less DB load.
     *
     * <p>Default: 1 second (dev). Production may use 500ms.
     */
    private Duration pollInterval = Duration.ofSeconds(1);

    /**
     * Maximum rows to claim per poll. Higher = more throughput per poll; shorter = less time
     * holding row locks.
     *
     * <p>Default: 100.
     */
    private int batchSize = 100;

    /**
     * After this many failed publish attempts, a row transitions to FAILED. FAILED rows require
     * operator intervention (inspect, fix, reset to PENDING via SQL).
     *
     * <p>Default: 10.
     */
    private int maxAttempts = 10;

    private Duration publishTimeout = Duration.ofSeconds(5);

    public Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(Duration publishTimeout) {
        this.publishTimeout = positiveDuration(publishTimeout, "publish-timeout");
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero() || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1ms");
        }
        return value;
    }

    /**
     * How long PUBLISHED rows are retained before the cleanup job removes them. A short audit trail
     * without unbounded growth.
     *
     * <p>Default: 7 days.
     */
    private Duration publishedRetention = Duration.ofDays(7);

    /**
     * How often the cleanup job runs.
     *
     * <p>Default: 1 hour.
     */
    private Duration cleanupInterval = Duration.ofHours(1);

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = positiveDuration(pollInterval, "poll-interval");
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("batch-size must be between 1 and 1000");
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("max-attempts must be positive");
        this.maxAttempts = maxAttempts;
    }

    public Duration getPublishedRetention() {
        return publishedRetention;
    }

    public void setPublishedRetention(Duration publishedRetention) {
        this.publishedRetention = positiveDuration(publishedRetention, "published-retention");
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = positiveDuration(cleanupInterval, "cleanup-interval");
    }
}

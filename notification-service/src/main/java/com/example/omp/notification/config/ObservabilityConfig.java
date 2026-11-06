package com.example.omp.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Observability configuration specific to notification-service.
 *
 * <p>This class exists to apply service-specific metric customizations that other services don't
 * need. For example:
 *
 * <ul>
 *   <li>Custom WebSocket connection metrics (added in Batch 8.3).
 *   <li>Custom subscription metrics.
 * </ul>
 *
 * <p><b>Batch 8.2 stub.</b> Registers baseline meter filters. Actual custom metrics are added in
 * later batches as the corresponding features are implemented.
 *
 * <p><b>Why a dedicated class?</b> Metric configuration is a cross-cutting concern. Keeping it
 * separate from functional config (WebSocket, Kafka, Redis) makes it easy to find and modify when
 * tuning observability.
 */
@Configuration
public class ObservabilityConfig {

    private final MeterRegistry registry;

    public ObservabilityConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void configureMetrics() {
        // Deny metrics with extremely high cardinality by default.
        // E.g., a metric labeled by user ID would explode. We add allow
        // rules as we introduce legitimate high-cardinality metrics.
        registry.config()
                .meterFilter(
                        MeterFilter.deny(
                                id -> {
                                    // Deny any metric that includes a "session_id" tag — session
                                    // IDs are per-connection and shouldn't be metric labels.
                                    return id.getTag("session_id") != null;
                                }));
    }
}

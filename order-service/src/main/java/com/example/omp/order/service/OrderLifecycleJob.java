package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.interceptor.ServiceTokenFile;
import com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "omp.order.lifecycle.enabled", havingValue = "true")
public class OrderLifecycleJob {
    private final OrderLifecycleCoordinator lifecycle;
    private final OrderFulfillmentQueue fulfillment;
    private final MeterRegistry metrics;
    private final Path tokenFile;

    public OrderLifecycleJob(
            OrderLifecycleCoordinator lifecycle,
            OrderFulfillmentQueue fulfillment,
            MeterRegistry metrics,
            @Value("${omp.order.recovery.token-file}") Path tokenFile) {
        this.lifecycle = lifecycle;
        this.fulfillment = fulfillment;
        this.metrics = metrics;
        this.tokenFile = tokenFile;
    }

    @Scheduled(fixedDelayString = "${omp.order.lifecycle.interval-ms:30000}")
    public void run() {
        try (var identity = ServiceTokenScope.open(ServiceTokenFile.read(tokenFile))) {
            lifecycle.retryDue();
            fulfillment.retryDue();
        } catch (RuntimeException | IOException failure) {
            metrics.counter("order.lifecycle.worker.failures").increment();
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Lifecycle worker will retry: {}", failure.getClass().getSimpleName());
        }
    }
}

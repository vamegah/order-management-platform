package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.interceptor.ServiceTokenFile;
import com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "omp.order.fulfillment.enabled", havingValue = "true")
public class OrderFulfillmentJob {
    private final OrderFulfillmentQueue queue;
    private final MeterRegistry metrics;
    private final Path tokenFile;

    public OrderFulfillmentJob(
            OrderFulfillmentQueue queue,
            MeterRegistry metrics,
            @Value("${omp.order.fulfillment.token-file}") Path tokenFile) {
        this.queue = queue;
        this.metrics = metrics;
        this.tokenFile = tokenFile;
    }

    @Scheduled(fixedDelayString = "${omp.order.fulfillment.interval-ms:30000}")
    public void run() {
        try (var identity = ServiceTokenScope.open(ServiceTokenFile.read(tokenFile))) {
            queue.retryDue();
        } catch (RuntimeException | IOException failure) {
            metrics.counter("order.fulfillment.worker.failures").increment();
            LoggerFactory.getLogger(getClass())
                    .warn("Fulfillment worker will retry: {}", failure.getClass().getSimpleName());
        }
    }
}

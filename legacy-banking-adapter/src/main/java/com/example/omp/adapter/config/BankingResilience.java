package com.example.omp.adapter.config;

import com.example.omp.adapter.port.BankTransportException;
import com.example.omp.adapter.port.BankingException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class BankingResilience {
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker breaker;
    private final Bulkhead bulkhead =
            Bulkhead.of(
                    "banking",
                    BulkheadConfig.custom()
                            .maxConcurrentCalls(8)
                            .maxWaitDuration(Duration.ZERO)
                            .build());
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public BankingResilience(
            CircuitBreakerRegistry registry, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.breaker = registry.circuitBreaker("banking");
        this.metrics = metrics;
        metrics.gauge(
                "banking.bulkhead.available",
                bulkhead,
                b -> b.getMetrics().getAvailableConcurrentCalls());
    }

    public <T> T execute(String operation, Supplier<T> call) {
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            return Bulkhead.decorateSupplier(
                            bulkhead,
                            () ->
                                    breaker.executeSupplier(
                                            () -> {
                                                for (int attempt = 1; ; attempt++) {
                                                    metrics.counter(
                                                                    "banking.soap.attempts",
                                                                    "operation",
                                                                    operation)
                                                            .increment();
                                                    try {
                                                        return call.get();
                                                    } catch (BankTransportException failure) {
                                                        if (attempt == 2) throw failure;
                                                        metrics.counter(
                                                                        "banking.soap.retries",
                                                                        "operation",
                                                                        operation)
                                                                .increment();
                                                        try {
                                                            Thread.sleep(100);
                                                        } catch (InterruptedException interrupted) {
                                                            Thread.currentThread().interrupt();
                                                            throw failure;
                                                        }
                                                    }
                                                }
                                            }))
                    .get();
        } catch (CallNotPermittedException
                | io.github.resilience4j.bulkhead.BulkheadFullException
                | io.github.resilience4j.core.exception.AcquirePermissionCancelledException
                        failure) {
            metrics.counter("banking.soap.rejected", "operation", operation).increment();
            throw new BankingException(
                    BankingException.Code.UPSTREAM_UNAVAILABLE, "Bank service unavailable");
        } finally {
            sample.stop(metrics.timer("banking.soap.duration", "operation", operation));
        }
    }

    @org.springframework.context.annotation.Bean
    static io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
            bankingFailureClassification() {
        return io.github.resilience4j.common.circuitbreaker.configuration
                .CircuitBreakerConfigCustomizer.of(
                "banking",
                builder ->
                        builder.ignoreException(
                                ex ->
                                        ex instanceof BankingException failure
                                                && failure.getCode().business()));
    }
}

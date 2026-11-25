package com.example.omp.adapter.config;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.adapter.port.*;
import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BankingResilienceTest {
    @Test
    void infrastructureOpensCircuitBusinessFaultsDoNotAndHalfOpenProbesCloseIt() throws Exception {
        var config =
                CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(2)
                        .slidingWindowSize(2)
                        .waitDurationInOpenState(Duration.ofMillis(50))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .ignoreException(
                                ex ->
                                        ex instanceof BankingException failure
                                                && failure.getCode().business())
                        .build();
        var registry = CircuitBreakerRegistry.of(config);
        var metrics = new SimpleMeterRegistry();
        var resilience = new BankingResilience(registry, metrics);
        var circuit = registry.circuitBreaker("banking");
        for (int n = 0; n < 3; n++)
            assertThatThrownBy(
                            () ->
                                    resilience.execute(
                                            "balance",
                                            () -> {
                                                throw new BankingException(
                                                        BankingException.Code.INSUFFICIENT_FUNDS,
                                                        "private");
                                            }))
                    .isInstanceOf(BankingException.class);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        for (int n = 0; n < 2; n++)
            assertThatThrownBy(
                            () ->
                                    resilience.execute(
                                            "balance",
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new BankTransportException(
                                                        BankingException.Code.UPSTREAM_TIMEOUT,
                                                        null);
                                            }))
                    .isInstanceOf(BankTransportException.class);
        assertThat(attempts).hasValue(4);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> resilience.execute("balance", () -> "unexpected"))
                .isInstanceOf(BankingException.class)
                .extracting("code")
                .isEqualTo(BankingException.Code.UPSTREAM_UNAVAILABLE);
        Thread.sleep(70);
        assertThat(
                        resilience.execute(
                                "balance",
                                () -> {
                                    assertThat(circuit.getState())
                                            .isEqualTo(CircuitBreaker.State.HALF_OPEN);
                                    return "recovered";
                                }))
                .isEqualTo("recovered");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(metrics.get("banking.soap.retries").counter().count()).isEqualTo(2);
    }

    @Test
    void tlsAndBackendFaultsNeverRetryAndInterruptedRetriesPreserveInterrupt() {
        var registry = CircuitBreakerRegistry.ofDefaults();
        var resilience = new BankingResilience(registry, new SimpleMeterRegistry());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (var code :
                java.util.List.of(
                        BankingException.Code.UPSTREAM_AUTHENTICATION,
                        BankingException.Code.UPSTREAM_ERROR))
            assertThatThrownBy(
                            () ->
                                    resilience.execute(
                                            "transfer",
                                            () -> {
                                                calls.incrementAndGet();
                                                throw new BankingException(code, "private");
                                            }))
                    .isInstanceOf(BankingException.class);
        assertThat(calls).hasValue(2);
        try {
            assertThatThrownBy(
                            () ->
                                    resilience.execute(
                                            "transfer",
                                            () -> {
                                                Thread.currentThread().interrupt();
                                                throw new BankTransportException(
                                                        BankingException.Code.UPSTREAM_UNAVAILABLE,
                                                        null);
                                            }))
                    .isInstanceOf(BankTransportException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

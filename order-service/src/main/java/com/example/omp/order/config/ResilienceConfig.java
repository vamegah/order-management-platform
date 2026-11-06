package com.example.omp.order.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j circuit breaker configuration.
 *
 * <p>Per-client overrides can be added here. Defaults:
 *
 * <ul>
 *   <li>50% failure rate threshold over a 20-call window.
 *   <li>10-second wait before half-open.
 *   <li>3 calls allowed in half-open state.
 * </ul>
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerConfigCustomizer productClientCustomizer() {
        return CircuitBreakerConfigCustomizer.of(
                "productClient",
                builder ->
                        builder.slidingWindowSize(20)
                                .failureRateThreshold(50f)
                                .waitDurationInOpenState(Duration.ofSeconds(10))
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .automaticTransitionFromOpenToHalfOpenEnabled(true));
    }

    @Bean
    public CircuitBreakerConfigCustomizer customerClientCustomizer() {
        return CircuitBreakerConfigCustomizer.of(
                "customerClient",
                builder ->
                        builder.slidingWindowSize(20)
                                .failureRateThreshold(50f)
                                .waitDurationInOpenState(Duration.ofSeconds(10))
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .automaticTransitionFromOpenToHalfOpenEnabled(true));
    }
}

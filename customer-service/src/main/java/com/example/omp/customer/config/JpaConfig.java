package com.example.omp.customer.config;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA configuration for the customer service. */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /**
     * Provides {@code Instant.now()} for audit fields. The default provider returns {@code
     * LocalDateTime}, which doesn't match our entity fields (typed as {@code Instant}).
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.<TemporalAccessor>of(Instant.now());
    }
}

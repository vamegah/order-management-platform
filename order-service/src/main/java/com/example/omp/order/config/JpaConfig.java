package com.example.omp.order.config;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA configuration. Enables auditing and provides an {@link Instant}-based time source so that
 * {@code @CreatedDate} and {@code @LastModifiedDate} fields (typed as {@link Instant}) are
 * populated correctly.
 *
 * <p>JPA auditing is what populates {@code created_at} and {@code updated_at} on entities when
 * they're saved. Without it, those columns would rely on DB defaults (which work for insert but not
 * for update).
 */
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

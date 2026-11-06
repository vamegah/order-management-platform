package com.example.omp.shared.events.outbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Auto-configuration for the transactional outbox.
 *
 * <p>Activates the outbox beans only when both JPA and Kafka are present on the classpath. Services
 * that don't produce events (e.g., product-service, customer-service) don't have Kafka on their
 * classpath, so this auto-configuration doesn't activate for them.
 *
 * <p><b>Activation conditions:</b>
 *
 * <ul>
 *   <li>{@code OutboxEvent} — must be on classpath (always true here).
 *   <li>{@code KafkaTemplate} — must be on classpath (services that produce events have
 *       spring-kafka; others don't).
 *   <li>{@code JpaRepository} — must be on classpath (services that produce events have
 *       spring-data-jpa; others don't).
 *   <li>{@code omp.outbox.enabled} — must be true (default) to activate the drainer.
 * </ul>
 *
 * <p>The producing service must register {@link OutboxEvent} in its entity scan and {@link
 * OutboxRepository} in its repository scan. Consumers without that repository do not activate this
 * module. Disabling the drainer keeps the writer available for transactional writes.
 */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration")
@ConditionalOnClass({KafkaTemplate.class, JpaRepository.class})
@ConditionalOnBean(OutboxRepository.class)
@EnableConfigurationProperties(OutboxDrainerProperties.class)
@org.springframework.context.annotation.Import({
    OutboxWriter.class,
    OutboxDrainer.class,
    OutboxMetrics.class
})
public class OutboxModuleAutoConfiguration {}

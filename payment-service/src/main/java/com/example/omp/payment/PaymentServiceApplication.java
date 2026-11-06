package com.example.omp.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Main entry point for the payment service.
 *
 * <p>Like inventory-service, this is a gRPC-only service:
 *
 * <ul>
 *   <li>No Tomcat, no REST endpoints.
 *   <li>gRPC server on port 9093 (application traffic).
 *   <li>Management HTTP on port 9094 (actuator).
 * </ul>
 *
 * <p>Domain: payment intents, confirmations, cancellations, refunds, and event streaming. The
 * service tracks payment state; the actual charge is made by an external provider (integrated in
 * Phase 5 via webhooks).
 *
 * <p><b>@EnableRetry</b> — activates Spring Retry's {@code @Retryable} support. Needed for handlers
 * that retry on optimistic-lock conflicts ({@code PaymentIntentHandler} and {@code RefundHandler}).
 */
@SpringBootApplication
@org.springframework.boot.persistence.autoconfigure.EntityScan(
        basePackages = {"com.example.omp.payment.domain", "com.example.omp.shared.events.outbox"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = {
            "com.example.omp.payment.repository",
            "com.example.omp.shared.events.outbox"
        })
@EnableJpaAuditing
@EnableRetry
@org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

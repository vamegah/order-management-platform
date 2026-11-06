package com.example.omp.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Main entry point for the order service.
 *
 * <p>This service owns orders and their lifecycle. Unlike product-service and customer-service,
 * order-service depends on OTHER services:
 *
 * <ul>
 *   <li>Reads product details (name, price) from {@code product-service} via Feign (REST).
 *   <li>Reads customer details (name, addresses) from {@code customer-service} via Feign (REST).
 *   <li>Reserves stock via {@code inventory-service} via gRPC.
 *   <li>Creates payment intents via {@code payment-service} via gRPC.
 * </ul>
 *
 * <p>It also publishes order lifecycle events to Kafka (ORDER_CREATED, ORDER_PLACED,
 * ORDER_CONFIRMED, ORDER_SHIPPED, ORDER_DELIVERED, ORDER_CANCELLED), which are consumed by the
 * notification-service and any future audit or analytics consumers.
 *
 * <p>It exposes a REST API under {@code /api/v1/orders} and reads/writes the {@code orders} schema
 * in the shared Postgres instance.
 *
 * <p>Authentication is via JWT issued by Keycloak. All endpoints require a valid token except
 * {@code /actuator/health} and {@code /actuator/info}.
 *
 * <p>Observability:
 *
 * <ul>
 *   <li>Prometheus metrics at {@code /actuator/prometheus}.
 *   <li>Structured JSON logs to stdout (shipped to Loki by Promtail).
 *   <li>OTLP spans exported to the OTel Collector (forwarded to Tempo).
 * </ul>
 *
 * <p><b>Cross-service reliability:</b> outbound calls (Feign and gRPC) are wrapped in Resilience4j
 * circuit breakers and retries. Events are recorded in the transactional outbox alongside aggregate
 * changes.
 *
 * @see com.example.omp.order.config.SecurityConfig
 * @see com.example.omp.order.web.OrderController
 * @see com.example.omp.order.events.OrderEventPublisher
 */
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.boot.autoconfigure.domain.EntityScan(
        basePackages = {"com.example.omp.order.domain", "com.example.omp.shared.events.outbox"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = {"com.example.omp.order.repository", "com.example.omp.shared.events.outbox"})
@SpringBootApplication
@EnableFeignClients
@EnableRetry
@ConfigurationPropertiesScan
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

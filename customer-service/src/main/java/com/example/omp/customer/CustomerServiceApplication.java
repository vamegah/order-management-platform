package com.example.omp.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Main entry point for the customer service.
 *
 * <p>This service owns customer profiles: customers, addresses, preferences. It exposes a REST API
 * under {@code /api/v1/} and reads/writes the {@code customers} schema in the shared Postgres
 * instance.
 *
 * <p>Authentication is via JWT issued by Keycloak (see File 12 for the realm definition). All
 * endpoints require a valid token except {@code /actuator/health} and {@code /actuator/info}.
 *
 * <p>Observability: this service exposes Prometheus metrics at {@code /actuator/prometheus}, writes
 * structured JSON logs to stdout (shipped to Loki by Promtail), and exports OTLP spans to the OTel
 * Collector (see File 20).
 *
 * @see com.example.omp.customer.config.SecurityConfig
 * @see com.example.omp.customer.web.CustomerController
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}

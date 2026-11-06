package com.example.omp.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Main entry point for the product service.
 *
 * <p>This service owns the product catalog: products, categories, pricing. It exposes a REST API
 * under {@code /api/v1/} and reads/writes the {@code products} schema in the shared Postgres
 * instance.
 *
 * <p>Authentication is via JWT issued by Keycloak (see File 12 for the realm definition). All
 * endpoints require a valid token except {@code /actuator/health} and {@code /actuator/info}.
 *
 * <p>Observability: this service exposes Prometheus metrics at {@code /actuator/prometheus}, writes
 * structured JSON logs to stdout (shipped to Loki by Promtail), and exports OTLP spans to the OTel
 * Collector (see File 20).
 *
 * @see com.example.omp.product.config.SecurityConfig
 * @see com.example.omp.product.web.ProductController
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}

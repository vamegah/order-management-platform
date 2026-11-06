package com.example.omp.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Main entry point for the inventory service.
 *
 * <p>Unlike the Phase 1 REST services, this application:
 *
 * <ul>
 *   <li>Exposes no business REST endpoints; servlet infrastructure hosts management only.
 *   <li>Starts an embedded gRPC server (via grpc-spring-boot-starter) on port 9091.
 *   <li>Exposes actuator endpoints on a separate management HTTP port (9092).
 * </ul>
 *
 * <p>The gRPC services are discovered via {@code @GrpcService} annotations. Interceptors handle
 * authentication, tracing, and exception translation.
 *
 * <p>This class has no {@code @EnableFeignClients} — inventory-service doesn't call other services
 * in Phase 3.
 */
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.boot.persistence.autoconfigure.EntityScan(
        basePackages = {"com.example.omp.inventory.domain", "com.example.omp.shared.events.outbox"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = {
            "com.example.omp.inventory.repository",
            "com.example.omp.shared.events.outbox"
        })
@SpringBootApplication
@EnableJpaAuditing
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

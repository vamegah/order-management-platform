package com.example.omp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Main entry point for the GraphQL gateway.
 *
 * <p>The gateway is a pure aggregation layer. It:
 *
 * <ul>
 *   <li>Exposes a single GraphQL endpoint ({@code /graphql}) that clients query with a flexible
 *       schema.
 *   <li>Translates each GraphQL query into one or more REST calls to {@code product-service},
 *       {@code customer-service}, and {@code order-service}.
 *   <li>Aggregates the responses into a single GraphQL response.
 * </ul>
 *
 * <p>Unlike the Phase 1 services, the gateway:
 *
 * <ul>
 *   <li>Owns no data (no JPA, no Flyway, no datasource).
 *   <li>Depends on all three Phase 1 services being available.
 *   <li>Uses Feign clients with the caller's JWT (token relay) to call downstream services.
 * </ul>
 *
 * <p>The GraphQL schema is defined in {@code src/main/resources/graphql/schema.graphqls} (added in
 * Batch 6.2). Resolvers are annotated with {@code @QueryMapping} and {@code @SchemaMapping} (added
 * in Batch 6.3).
 *
 * @see com.example.omp.gateway.config.SecurityConfig
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class GraphqlGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphqlGatewayApplication.class, args);
    }
}

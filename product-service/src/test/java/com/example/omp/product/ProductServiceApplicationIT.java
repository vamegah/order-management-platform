package com.example.omp.product;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: verifies the application context loads.
 *
 * <p>If this test fails, nothing else will work. Common causes:
 *
 * <ul>
 *   <li>Missing or misconfigured bean
 *   <li>Circular dependency
 *   <li>Missing property with no default
 *   <li>Invalid configuration in application.yml
 * </ul>
 *
 * <p>Extends {@link AbstractIntegrationTest} so Testcontainers provides a real Postgres — the
 * context needs a datasource to start.
 */
class ProductServiceApplicationIT extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
        // Intentionally empty. The assertion is that Spring Boot starts
        // without throwing. If it doesn't, the test fails with the actual
        // exception in the logs.
    }
}

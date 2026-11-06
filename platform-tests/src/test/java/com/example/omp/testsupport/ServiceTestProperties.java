package com.example.omp.testsupport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Keeps each service's configuration, migrations and schema separate on the shared test classpath.
 */
public final class ServiceTestProperties {
    private ServiceTestProperties() {}

    public static Map<String, Object> forService(String service, String schema) {
        SharedContainers.ensureStarted();
        Map<String, Object> properties = new LinkedHashMap<>();
        Path resources = Path.of(service, "src/main/resources").toAbsolutePath();
        properties.put(
                "spring.config.location",
                resources.resolve("application.yml").toUri() + ",classpath:application-test.yml");
        properties.put("spring.datasource.url", SharedContainers.jdbcUrlFor(schema));
        properties.put("spring.datasource.username", "omp_test");
        properties.put("spring.datasource.password", "omp_test");
        properties.put("spring.jpa.properties.hibernate.default_schema", schema);
        properties.put("spring.flyway.schemas", schema);
        properties.put("spring.flyway.default-schema", schema);
        properties.put(
                "spring.flyway.locations",
                "filesystem:" + resources.resolve("db/migration").toString().replace('\\', '/'));
        properties.put("spring.kafka.bootstrap-servers", SharedContainers.kafkaBootstrap());
        properties.put("spring.data.redis.host", SharedContainers.REDIS.getHost());
        properties.put("spring.data.redis.port", SharedContainers.REDIS.getMappedPort(6379));
        properties.put("spring.data.redis.password", "");
        properties.put("server.port", 0);
        properties.put("management.server.port", 0);
        // gRPC arrives transitively with payment-service; notification has no RPC server.
        properties.put("grpc.server.port", schema.equals("notifications") ? -1 : 0);
        properties.put("grpc.server.security.enabled", false);
        properties.put("webhook.kafka.replication-factor", 1);
        properties.put("webhook.kafka.delivery-concurrency", 1);
        properties.put("webhook.kafka.event-concurrency", 1);
        properties.put("webhook.kafka.dlq-concurrency", 1);
        properties.put("spring.kafka.listener.concurrency", 1);
        return properties;
    }

    public static void register(DynamicPropertyRegistry registry, String service, String schema) {
        forService(service, schema).forEach((key, value) -> registry.add(key, () -> value));
    }
}

package com.example.omp.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton infrastructure shared by the in-process platform integration suites.
 *
 * <p>Started once on first reference; JVM shutdown stops them. This avoids spinning up a fresh
 * Postgres, Kafka and Redis for every test class. The packaged container journey owns separate
 * infrastructure.
 *
 * <p>The containers are NOT annotated with @Container / @Testcontainers; instead they are started
 * explicitly. Tests reference the static fields directly.
 */
public final class SharedContainers {

    private SharedContainers() {}

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("postgres:16-alpine")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("omp")
                    .withUsername("omp_test")
                    .withPassword("omp_test")
                    .withInitScript("db/init-test-schemas.sql");

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        REDIS.stop();
                                    } catch (Exception ignored) {
                                    }
                                    try {
                                        KAFKA.stop();
                                    } catch (Exception ignored) {
                                    }
                                    try {
                                        POSTGRES.stop();
                                    } catch (Exception ignored) {
                                    }
                                }));
    }

    public static void ensureStarted() {
        // Trigger class initialization.
    }

    public static String jdbcUrlFor(String schema) {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    }

    public static String kafkaBootstrap() {
        return KAFKA.getBootstrapServers();
    }
}

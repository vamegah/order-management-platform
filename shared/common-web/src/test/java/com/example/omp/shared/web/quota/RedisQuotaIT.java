package com.example.omp.shared.web.quota;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class RedisQuotaIT {
    private static final String IMAGE =
            "redis:8.6-alpine@sha256:91c71f9ea78fa3fe268ff832a985c48e592244f707993773f8b3e1b244ac1137";

    @Test
    void productionAclPersistsBucketsAndStoreOutageFailsClosedWithinTwoSeconds(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var password = temporary.resolve("password");
        java.nio.file.Files.writeString(password, "isolated-quota-password-12345");
        var script = java.nio.file.Path.of("../../infrastructure/redis/runtime.sh");
        try (var redis =
                new GenericContainer<>(IMAGE)
                        .withExposedPorts(6379)
                        .withTmpFs(Map.of("/run/omp", "rw,mode=1777"))
                        .withCopyToContainer(
                                org.testcontainers.images.builder.Transferable.of(
                                        java.nio.file.Files.readAllBytes(script), 0444),
                                "/app/redis-runtime.sh")
                        .withCopyToContainer(
                                org.testcontainers.images.builder.Transferable.of(
                                        "isolated-quota-password-12345", 0444),
                                "/run/secrets/redis_password")
                        .withCopyToContainer(
                                org.testcontainers.images.builder.Transferable.of(
                                        "isolated-monitor-password-12345", 0444),
                                "/run/secrets/redis_monitor_password")
                        .withCommand("sh", "/app/redis-runtime.sh", "quota")) {
            redis.start();
            var properties =
                    new QuotaProperties(
                            true,
                            "persistence",
                            redis.getHost(),
                            redis.getMappedPort(6379),
                            "quota",
                            password,
                            false,
                            Duration.ofMillis(250),
                            16,
                            1);
            try (var store = new RedisQuotaStore(properties)) {
                assertThat(store.acquire("omp:quota:v1:{persistence_READ}:one", 1)).isZero();
                assertThat(store.acquire("omp:quota:v1:{persistence_READ}:one", 1)).isPositive();
            }
            redis.getDockerClient().restartContainerCmd(redis.getContainerId()).exec();
            // Docker Desktop may reassign an ephemeral published port on restart.
            var binding =
                    redis.getDockerClient()
                            .inspectContainerCmd(redis.getContainerId())
                            .exec()
                            .getNetworkSettings()
                            .getPorts()
                            .getBindings()
                            .get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0];
            var resumed =
                    new QuotaProperties(
                            true,
                            "persistence",
                            redis.getHost(),
                            Integer.parseInt(binding.getHostPortSpec()),
                            "quota",
                            password,
                            false,
                            Duration.ofMillis(250),
                            16,
                            1);

            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                try (var store = new RedisQuotaStore(resumed)) {
                                    assertThat(
                                                    store.acquire(
                                                            "omp:quota:v1:{persistence_READ}:one",
                                                            1))
                                            .isPositive();
                                }
                            });
            assertThat(redis.execInContainer("redis-cli", "PING").getStdout()).contains("NOAUTH");
            try (var store = new RedisQuotaStore(resumed)) {
                // Establish the connection before pausing the actual Redis server.
                store.acquire("omp:quota:v1:{persistence_READ}:two", 1);
                redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
                try {
                    long start = System.nanoTime();
                    var decision =
                            new QuotaService(
                                            store,
                                            properties,
                                            new io.micrometer.core.instrument.simple
                                                    .SimpleMeterRegistry())
                                    .acquire(QuotaPolicy.WRITE, "issuer", "subject");
                    assertThat(decision).isEqualTo(new QuotaService.Decision(false, false, 1));
                    assertThat(Duration.ofNanos(System.nanoTime() - start))
                            .isLessThan(Duration.ofSeconds(2));
                } finally {
                    redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
                }
            }
        }
    }

    @Test
    void independentIdentitiesCannotSpendEachOthersTokensAndActiveCatalogueIsBounded()
            throws Exception {
        try (var redis = new GenericContainer<>(IMAGE).withExposedPorts(6379)) {
            redis.start();
            var properties =
                    new QuotaProperties(
                            true,
                            "bounded",
                            redis.getHost(),
                            redis.getMappedPort(6379),
                            "",
                            null,
                            false,
                            Duration.ofMillis(250),
                            16,
                            1);
            try (var first = new RedisQuotaStore(properties);
                    var second = new RedisQuotaStore(properties)) {
                String prefix = "omp:quota:v1:{bounded_READ}:";
                for (int i = 0; i < 16; i++)
                    assertThat((i % 2 == 0 ? first : second).acquire(prefix + i, 1)).isZero();
                assertThat(second.acquire(prefix + "0", 1)).isPositive();
                assertThat(first.acquire(prefix + "new", 1)).isPositive();
                assertThat(
                                redis.execInContainer("redis-cli", "ZCARD", prefix + "identities")
                                        .getStdout()
                                        .strip())
                        .isEqualTo("16");
                assertThat(
                                redis.execInContainer("redis-cli", "EXISTS", prefix + "new")
                                        .getStdout()
                                        .strip())
                        .isEqualTo("0");
                redis.execInContainer(
                        "redis-cli", "ZADD", prefix + "identities", "0", prefix + "0");
                redis.execInContainer("redis-cli", "DEL", prefix + "0");
                assertThat(first.acquire(prefix + "new", 1)).isZero();
                assertThat(
                                redis.execInContainer("redis-cli", "ZCARD", prefix + "identities")
                                        .getStdout()
                                        .strip())
                        .isEqualTo("16");
            }
        }
    }

    @Test
    void independentReplicasShareOneAtomicQuotaAndExpireBoundedKeys() throws Exception {
        try (var redis = new GenericContainer<>(IMAGE).withExposedPorts(6379)) {
            redis.start();
            var properties =
                    new QuotaProperties(
                            true,
                            "replica",
                            redis.getHost(),
                            redis.getMappedPort(6379),
                            "",
                            null,
                            false,
                            Duration.ofMillis(250),
                            16,
                            1);
            try (var first = new RedisQuotaStore(properties);
                    var second = new RedisQuotaStore(properties)) {
                first.check();
                second.check();
                var executor = Executors.newFixedThreadPool(16);
                try {
                    var results = new ArrayList<Future<Long>>();
                    for (int i = 0; i < 80; i++) {
                        final int replica = i;
                        results.add(
                                executor.submit(
                                        () ->
                                                (replica % 2 == 0 ? first : second)
                                                        .acquire("omp:quota:replicas", 20)));
                    }
                    int accepted = 0;
                    for (var result : results)
                        if (result.get(10, TimeUnit.SECONDS) == 0) accepted++;
                    assertThat(accepted).isEqualTo(20);
                    long ttl =
                            Long.parseLong(
                                    redis.execInContainer("redis-cli", "PTTL", "omp:quota:replicas")
                                            .getStdout()
                                            .strip());
                    assertThat(ttl).isBetween(1L, 120000L);
                    redis.execInContainer("redis-cli", "DEL", "omp:quota:replicas");
                    assertThat(second.acquire("omp:quota:replicas", 20)).isZero();
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }
}

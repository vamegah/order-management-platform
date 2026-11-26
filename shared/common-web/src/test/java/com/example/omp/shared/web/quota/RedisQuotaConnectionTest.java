package com.example.omp.shared.web.quota;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisQuotaConnectionTest {
    @Test
    @SuppressWarnings("unchecked")
    void oneConnectionAttemptBoundsConcurrentRequestsAndReconnectsAfterClosure(
            @TempDir Path directory) throws Exception {
        var password = directory.resolve("credential");
        Files.writeString(password, "isolated-unit-fixture");
        var properties =
                new QuotaProperties(
                        true,
                        "test",
                        "localhost",
                        6379,
                        "",
                        password,
                        true,
                        Duration.ofMillis(250),
                        16,
                        1);
        var client = mock(RedisClient.class);
        var connection =
                (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        var commands = (RedisCommands<String, String>) mock(RedisCommands.class);
        when(connection.sync()).thenReturn(commands);
        when(connection.isOpen()).thenReturn(true);
        when(commands.ping()).thenReturn("PONG");
        when(commands.eval(
                        anyString(),
                        eq(ScriptOutputType.INTEGER),
                        any(String[].class),
                        any(String[].class)))
                .thenReturn(0L);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.connect())
                .thenAnswer(
                        i -> {
                            entered.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return connection;
                        });
        var worker = Executors.newSingleThreadExecutor();
        try (var factory = mockStatic(RedisClient.class)) {
            factory.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
            try (var store = new RedisQuotaStore(properties)) {
                var startup = worker.submit(store::check);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                try {
                    assertThatThrownBy(() -> store.acquire("omp:quota:v1:{test_READ}:identity", 2))
                            .hasMessage("Quota connection is initializing");
                    assertThat(Duration.ofNanos(System.nanoTime() - start))
                            .isLessThan(Duration.ofSeconds(1));
                } finally {
                    release.countDown();
                }
                startup.get(5, TimeUnit.SECONDS);
                assertThat(store.acquire("omp:quota:v1:{test_READ}:identity", 2)).isZero();
                verify(client, times(1)).connect();
                when(connection.isOpen()).thenReturn(false);
                store.check();
                verify(client, times(2)).connect();
                verify(connection, times(2)).setTimeout(Duration.ofMillis(250));
            }
            verify(connection).close();
            verify(client).shutdown();
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}

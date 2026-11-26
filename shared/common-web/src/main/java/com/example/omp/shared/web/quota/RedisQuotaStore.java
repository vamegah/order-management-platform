package com.example.omp.shared.web.quota;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.nio.file.Files;

/** Shared token bucket. Redis TIME avoids replica clock skew; EVAL is a single atomic decision. */
public final class RedisQuotaStore implements QuotaStore, AutoCloseable {
    static final String SCRIPT =
            """
            local nowParts = redis.call('TIME')
            local now = nowParts[1] * 1000 + math.floor(nowParts[2] / 1000)
            local capacity = tonumber(ARGV[1])
            local maximum = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if not redis.call('ZSCORE', KEYS[2], KEYS[1]) and redis.call('ZCARD', KEYS[2]) >= maximum then
                local first = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
                return math.max(1, math.ceil(tonumber(first[2]) - now))
            end
            redis.call('ZADD', KEYS[2], now + 120000, KEYS[1])
            redis.call('PEXPIRE', KEYS[2], 120000)
            local previous = redis.call('HMGET', KEYS[1], 'tokens', 'at')
            local at = tonumber(previous[2]) or now
            local tokens = tonumber(previous[1]) or capacity
            tokens = math.min(capacity, tokens + math.max(0, now - at) * capacity / 60000)
            local wait = 0
            if tokens >= 1 then tokens = tokens - 1
            else wait = math.ceil((1 - tokens) * 60000 / capacity) end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'at', math.max(now, at))
            redis.call('PEXPIRE', KEYS[1], 120000)
            return wait
            """;
    private final RedisClient client;
    private final int maxIdentities;
    private volatile StatefulRedisConnection<String, String> connection;
    private final java.util.concurrent.locks.ReentrantLock connecting =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.time.Duration commandTimeout;

    public RedisQuotaStore(QuotaProperties properties) throws IOException {
        this.maxIdentities = properties.maxIdentities();
        this.commandTimeout = properties.timeout();
        var uri =
                RedisURI.Builder.redis(properties.host(), properties.port())
                        .withSsl(properties.tls())
                        .withTimeout(java.time.Duration.ofSeconds(1));
        if (properties.passwordFile() != null) {
            if (Files.size(properties.passwordFile()) > 4096)
                throw new IOException("Quota credential exceeds limit");
            String password = Files.readString(properties.passwordFile()).strip();
            if (password.isEmpty()) throw new IOException("Empty quota credential");
            if (properties.username() != null && !properties.username().isBlank())
                uri.withAuthentication(properties.username(), password.toCharArray());
            else uri.withPassword(password.toCharArray());
        }
        this.client = RedisClient.create(uri.build());
        this.client.setOptions(
                ClientOptions.builder()
                        .socketOptions(
                                SocketOptions.builder()
                                        .connectTimeout(java.time.Duration.ofSeconds(1))
                                        .build())
                        .timeoutOptions(TimeoutOptions.enabled())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .requestQueueSize(128)
                        .build());
    }

    @Override
    public void check() {
        connection().sync().ping();
    }

    @Override
    public long acquire(String key, int capacity) {
        return connection()
                .sync()
                .eval(
                        SCRIPT,
                        ScriptOutputType.INTEGER,
                        new String[] {key, key.substring(0, key.lastIndexOf(':')) + ":identities"},
                        Integer.toString(capacity),
                        Integer.toString(maxIdentities));
    }

    private StatefulRedisConnection<String, String> connection() {
        var active = connection;
        if (active != null && active.isOpen()) return active;
        // Requests never queue behind another connection attempt during a store outage.
        if (!connecting.tryLock())
            throw new IllegalStateException("Quota connection is initializing");
        try {
            if (connection == null || !connection.isOpen()) {
                var established = client.connect();
                // The one-second handshake budget is separate from the normal 250ms command budget.
                established.setTimeout(commandTimeout);
                connection = established;
            }
            return connection;
        } finally {
            connecting.unlock();
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) connection.close();
        client.shutdown();
    }
}

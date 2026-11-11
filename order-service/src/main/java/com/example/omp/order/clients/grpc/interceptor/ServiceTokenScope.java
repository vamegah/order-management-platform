package com.example.omp.order.clients.grpc.interceptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Scope for a background identity; never inherited by request threads or persisted in the journal.
 */
public final class ServiceTokenScope implements AutoCloseable {
    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();
    private final String previous;

    private ServiceTokenScope(String token) {
        previous = TOKEN.get();
        TOKEN.set(token);
    }

    public static ServiceTokenScope open(String token) {
        if (token == null || token.isBlank())
            throw new IllegalArgumentException("Service token required");
        return new ServiceTokenScope(token);
    }

    /**
     * Runs trusted, order-owned downstream work with its projected identity. An already active
     * scope is preserved so nested lifecycle operations share the same bounded identity. When
     * recovery is not configured, the caller identity continues to be relayed.
     */
    public static <T> T withProjectedIdentity(String tokenFile, Supplier<T> operation) {
        if (tokenFile == null || tokenFile.isBlank() || isActive()) return operation.get();
        try (var identity = open(ServiceTokenFile.read(Path.of(tokenFile)))) {
            return operation.get();
        } catch (IOException failure) {
            throw new IllegalStateException("Projected workload identity is unavailable");
        }
    }

    /** Whether a bounded service identity is explicitly scoped on this thread. */
    public static boolean isActive() {
        return TOKEN.get() != null;
    }

    static String current() {
        return TOKEN.get();
    }

    @Override
    public void close() {
        if (previous == null) TOKEN.remove();
        else TOKEN.set(previous);
    }

    @Override
    public String toString() {
        return "ServiceTokenScope[redacted]";
    }
}

package com.example.omp.order.clients.grpc.interceptor;

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

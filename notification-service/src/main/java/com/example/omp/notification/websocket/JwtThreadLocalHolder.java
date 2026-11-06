package com.example.omp.notification.websocket;

/**
 * Thread-local storage for the current STOMP session's JWT.
 *
 * <p>REST services use Spring Security's {@code SecurityContextHolder} to hold the authenticated
 * user. But that holder is populated by HTTP filters and doesn't apply to STOMP message handling.
 *
 * <p>When a STOMP SUBSCRIBE frame triggers ownership verification (which makes REST calls via
 * Feign), we need to forward the client's JWT. This holder is populated by the handler before the
 * call and cleared after.
 *
 * <p><b>Usage:</b>
 *
 * <pre>
 *   try {
 *       JwtThreadLocalHolder.set(clientJwt);
 *       ownershipVerifier.verifyOrderOwnership(orderId, principal);
 *   } finally {
 *       JwtThreadLocalHolder.clear();
 *   }
 * </pre>
 */
public final class JwtThreadLocalHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private JwtThreadLocalHolder() {}

    public static void set(String jwt) {
        HOLDER.set(jwt);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

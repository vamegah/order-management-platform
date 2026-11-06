package com.example.omp.notification.subscription;

import com.example.omp.notification.websocket.JwtPrincipal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A single subscription: one client's interest in one destination.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>Created when a client sends a SUBSCRIBE frame.
 *   <li>Validated (destination is well-formed, authorization passes).
 *   <li>Registered in the {@link SubscriptionRegistry}.
 *   <li>Removed on UNSUBSCRIBE, DISCONNECT, or session expiry.
 * </ul>
 *
 * <p>Immutable. Regeneration on re-subscribe (e.g., after a reconnection) creates a new instance
 * with a new ID.
 */
public record Subscription(
        String id,
        String sessionId,
        String destination,
        TopicType type,
        String resourceId,
        String userId,
        String username,
        Set<String> roles,
        Instant subscribedAt) {

    /** Creates a subscription from a parsed destination and a Principal. */
    public static Subscription create(
            String sessionId,
            String destination,
            TopicType type,
            String resourceId,
            JwtPrincipal principal) {
        return create(
                UUID.randomUUID().toString(), sessionId, destination, type, resourceId, principal);
    }

    public static Subscription create(
            String id,
            String sessionId,
            String destination,
            TopicType type,
            String resourceId,
            JwtPrincipal principal) {
        return new Subscription(
                id,
                sessionId,
                destination,
                type,
                resourceId,
                principal.sub(),
                principal.username(),
                principal.roles(),
                Instant.now());
    }

    public boolean isAdmin() {
        return roles.contains("admin");
    }
}

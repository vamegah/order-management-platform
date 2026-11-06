package com.example.omp.notification.websocket;

import java.security.Principal;
import java.util.Set;

/**
 * The authenticated principal associated with a STOMP session.
 *
 * <p>Spring Security's STOMP integration populates this from the CONNECT frame's JWT. It's attached
 * to the session and available in {@code @MessageMapping} handlers, in {@code ChannelInterceptor}
 * calls, and in subscription destinations ({@code /user/{id}/...}).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code sub} — Keycloak user ID (JWT {@code sub} claim).
 *   <li>{@code username} — {@code preferred_username} claim.
 *   <li>{@code email} — email claim.
 *   <li>{@code roles} — realm roles from {@code realm_access.roles}.
 * </ul>
 *
 * <p>The verified {@code customer_id} claim avoids a customer lookup during subscription checks.
 * Tokens without that claim use customer-service's {@code /me} endpoint. The stable subject is the
 * broker user identity; display names never determine private message routing.
 */
public record JwtPrincipal(
        String sub, String username, String email, Set<String> roles, java.util.UUID customerId)
        implements Principal {

    public JwtPrincipal {
        // Defensive copy of roles.
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    public JwtPrincipal(String sub, String username, String email, Set<String> roles) {
        this(sub, username, email, roles, null);
    }

    @Override
    public String getName() {
        return sub;
    }

    public boolean isAdmin() {
        return roles.contains("admin");
    }
}

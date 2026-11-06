package com.example.omp.notification.websocket;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * STOMP {@code ChannelInterceptor} that authenticates incoming CONNECT frames.
 *
 * <p><b>Flow:</b>
 *
 * <ol>
 *   <li>A WebSocket client sends a STOMP CONNECT frame with an {@code Authorization: Bearer <jwt>}
 *       header.
 *   <li>This interceptor extracts the JWT.
 *   <li>Validates it via the {@link JwtDecoder} (same decoder used for REST).
 *   <li>On success, attaches a {@link JwtPrincipal} to the message.
 *   <li>On failure, throws {@link BadCredentialsException}, which the framework translates into a
 *       STOMP ERROR frame.
 * </ol>
 *
 * <p><b>Why not the HTTP filter chain?</b> The HTTP filter chain runs during the WebSocket
 * handshake, not per-message. Authentication must happen per STOMP session (after the handshake),
 * so a message-level interceptor is the correct place.
 *
 * <p><b>What about SUBSCRIBE and SEND frames?</b> Once a session is authenticated at CONNECT,
 * Spring Security attaches the Principal to the session. Subsequent frames carry the Principal
 * automatically. This interceptor only handles CONNECT (and DISCONNECT for cleanup).
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (!StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            if (!(accessor.getUser() instanceof JwtPrincipal))
                throw new BadCredentialsException("Authenticated STOMP session required");
            var attributes = accessor.getSessionAttributes();
            if (attributes != null
                    && attributes.get("jwtExpiresAt") instanceof java.time.Instant expiry
                    && !java.time.Instant.now().isBefore(expiry))
                throw new BadCredentialsException("STOMP session token expired");
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        List<String> authHeaders = accessor.getNativeHeader(AUTHORIZATION_HEADER);
        if (authHeaders == null || authHeaders.isEmpty()) {
            log.debug("STOMP CONNECT without Authorization header");
            throw new BadCredentialsException("Missing Authorization header");
        }

        String header = authHeaders.get(0);
        if (!header.startsWith(BEARER_PREFIX)) {
            log.debug("STOMP CONNECT with malformed Authorization header");
            throw new BadCredentialsException("Authorization header must start with 'Bearer '");
        }

        String token = header.substring(BEARER_PREFIX.length());
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.debug("STOMP CONNECT with invalid JWT: {}", e.getMessage());
            throw new BadCredentialsException("Invalid JWT");
        }

        JwtPrincipal principal = toPrincipal(jwt);
        accessor.setUser(principal);

        // In authenticate(), after successful decode:
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put("jwt", new SessionToken(token));
            if (jwt.getExpiresAt() != null)
                sessionAttributes.put("jwtExpiresAt", jwt.getExpiresAt());
        }
        accessor.removeNativeHeader(AUTHORIZATION_HEADER);

        log.info(
                "STOMP session authenticated: user={} sub={}",
                principal.username(),
                principal.sub());
    }

    @SuppressWarnings("unchecked")
    private JwtPrincipal toPrincipal(Jwt jwt) {
        String sub = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");

        Set<String> roles = Set.of();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            Object rolesObj = map.get("roles");
            if (rolesObj instanceof List<?> list) {
                roles = ((List<String>) list).stream().collect(Collectors.toSet());
            }
        }

        if (sub == null || sub.isBlank()) throw new BadCredentialsException("JWT subject required");
        String customer = jwt.getClaimAsString("customer_id");
        try {
            return new JwtPrincipal(
                    sub,
                    username,
                    email,
                    roles,
                    customer == null ? null : java.util.UUID.fromString(customer));
        } catch (IllegalArgumentException ex) {
            throw new BadCredentialsException("Invalid customer identity");
        }
    }
}

package com.example.omp.notification.subscription;

import com.example.omp.notification.websocket.JwtPrincipal;
import com.example.omp.notification.websocket.JwtThreadLocalHolder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Intercepts STOMP SUBSCRIBE and UNSUBSCRIBE frames.
 *
 * <p><b>SUBSCRIBE flow:</b>
 *
 * <ol>
 *   <li>Parse the destination into a {@link TopicType} and resource ID.
 *   <li>Reject unknown destinations with a STOMP ERROR.
 *   <li>Verify ownership (via {@link OwnershipVerifier}).
 *   <li>Register the subscription in {@link SubscriptionRegistry}.
 * </ol>
 *
 * <p><b>UNSUBSCRIBE flow:</b> remove the matching subscription(s) from the registry.
 *
 * <p>This runs after the CONNECT was authenticated, so the Principal is already set on the session.
 *
 * <p><b>JWT forwarding:</b> during ownership verification, the client's JWT is placed in {@link
 * JwtThreadLocalHolder} so the Feign interceptor can forward it. This is required because STOMP
 * handlers don't populate the HTTP SecurityContext.
 *
 * <p><b>Note:</b> the JWT is available on the CONNECT frame only. Spring doesn't keep it after
 * authentication (the Principal is set, but the raw JWT isn't preserved). To recover the JWT for
 * Feign calls, we stash it on the session's attributes during CONNECT.
 */
@Component
public class SubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionInterceptor.class);

    private final SubscriptionRegistry registry;
    private final OwnershipVerifier ownershipVerifier;

    public SubscriptionInterceptor(
            SubscriptionRegistry registry, OwnershipVerifier ownershipVerifier) {
        this.registry = registry;
        this.ownershipVerifier = ownershipVerifier;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            handleSubscribe(accessor);
        } else if (StompCommand.UNSUBSCRIBE.equals(accessor.getCommand())) {
            handleUnsubscribe(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            throw new SubscriptionUnauthorizedException("Client publishing is not allowed");
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())
                && accessor.getSessionId() != null) {
            registry.removeSession(accessor.getSessionId());
        }

        return message;
    }

    // =========================================================================
    // Subscribe
    // =========================================================================

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        JwtPrincipal principal = extractPrincipal(accessor);
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null || subscriptionId.isBlank())
            throw new SubscriptionUnauthorizedException(
                    "Session and subscription IDs are required");

        log.debug(
                "SUBSCRIBE session={} dest={} user={}",
                sessionId,
                destination,
                principal != null ? principal.getName() : "?");

        // Parse destination.
        var parsed =
                TopicType.parse(destination)
                        .orElseThrow(
                                () ->
                                        new SubscriptionUnauthorizedException(
                                                "Unknown destination: " + destination));

        String resourceId = parsed.resourceId();
        // Verify ownership.
        try {
            JwtThreadLocalHolder.set(extractRawJwt(accessor));
            switch (parsed.type()) {
                case ORDERS ->
                        ownershipVerifier.verifyOrderOwnership(parsed.resourceId(), principal);
                case PAYMENTS ->
                        ownershipVerifier.verifyPaymentOwnership(parsed.resourceId(), principal);
                case INVENTORY -> {
                    if (!principal.isAdmin()) {
                        throw new SubscriptionUnauthorizedException(
                                "Inventory subscriptions require admin role");
                    }
                }
                case NOTIFICATIONS ->
                        resourceId = ownershipVerifier.resolveCustomerId(principal).toString();
            }
        } finally {
            JwtThreadLocalHolder.clear();
        }

        // Register.
        Subscription subscription =
                Subscription.create(
                        subscriptionId,
                        sessionId,
                        destination,
                        parsed.type(),
                        resourceId,
                        principal);
        registry.register(subscription);

        log.info(
                "Subscribed: session={} user={} dest={}",
                sessionId,
                principal.getName(),
                destination);
    }

    // =========================================================================
    // Unsubscribe
    // =========================================================================

    private void handleUnsubscribe(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        extractPrincipal(accessor);
        if (sessionId == null || subscriptionId == null || subscriptionId.isBlank())
            throw new SubscriptionUnauthorizedException(
                    "Session and subscription IDs are required");
        registry.unregister(sessionId, subscriptionId);

        log.debug("UNSUBSCRIBE session={} subId={}", sessionId, subscriptionId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JwtPrincipal extractPrincipal(StompHeaderAccessor accessor) {
        java.security.Principal principal = accessor.getUser();
        if (!(principal instanceof JwtPrincipal jwtPrincipal)) {
            throw new SubscriptionUnauthorizedException(
                    "STOMP session has no authenticated principal");
        }
        return jwtPrincipal;
    }

    /**
     * Retrieves the raw JWT stored on the session during CONNECT. The token is stored in session
     * attributes by the auth interceptor.
     */
    private String extractRawJwt(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        Object jwt = sessionAttributes.get("jwt");
        if (jwt instanceof com.example.omp.notification.websocket.SessionToken token)
            return token.value();
        return jwt instanceof String s ? s : null;
    }
}

package com.example.omp.notification.subscription;

import com.example.omp.notification.websocket.JwtPrincipal;
import com.example.omp.notification.websocket.JwtThreadLocalHolder;
import com.example.omp.notification.websocket.SessionToken;
import com.example.omp.notification.websocket.WebSocketSessions;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** Caller-scoped ownership with cancellable, session-bound subscription registrations. */
@Component
public class SubscriptionInterceptor implements ChannelInterceptor {
    private final OwnershipVerifier ownership;
    private final WebSocketSessions sessions;

    public SubscriptionInterceptor(OwnershipVerifier ownership, WebSocketSessions sessions) {
        this.ownership = ownership;
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null) return message;
        if (headers.getCommand() == StompCommand.SUBSCRIBE) subscribe(headers);
        else if (headers.getCommand() == StompCommand.UNSUBSCRIBE) {
            principal(headers);
            ids(headers);
            sessions.unsubscribe(headers.getSessionId(), headers.getSubscriptionId());
        } else if (headers.getCommand() == StompCommand.SEND)
            throw new SubscriptionUnauthorizedException("Client publishing is not allowed");
        else if (headers.getCommand() == StompCommand.DISCONNECT && headers.getSessionId() != null)
            sessions.closed(headers.getSessionId());
        return message;
    }

    private void subscribe(StompHeaderAccessor headers) {
        JwtPrincipal principal = principal(headers);
        ids(headers);
        var destination =
                TopicType.parse(headers.getDestination())
                        .orElseThrow(
                                () -> new SubscriptionUnauthorizedException("Unknown destination"));
        String session = headers.getSessionId(), id = headers.getSubscriptionId();
        var ticket = sessions.beginSubscription(session, id);
        try {
            var attrs = headers.getSessionAttributes();
            Object raw = attrs == null ? null : attrs.get("jwt");
            JwtThreadLocalHolder.set(
                    raw instanceof SessionToken token
                            ? token.value()
                            : raw instanceof String token ? token : null);
            String resource = destination.resourceId();
            switch (destination.type()) {
                case ORDERS -> ownership.verifyOrderOwnership(resource, principal);
                case PAYMENTS -> ownership.verifyPaymentOwnership(resource, principal);
                case INVENTORY -> {
                    if (!principal.isAdmin())
                        throw new SubscriptionUnauthorizedException(
                                "Inventory subscriptions require admin role");
                }
                case NOTIFICATIONS -> resource = ownership.resolveCustomerId(principal).toString();
            }
            sessions.register(
                    Subscription.create(
                            id,
                            session,
                            headers.getDestination(),
                            destination.type(),
                            resource,
                            principal),
                    ticket);
        } finally {
            JwtThreadLocalHolder.clear();
            sessions.cancelPending(session, id, ticket);
        }
    }

    private static void ids(StompHeaderAccessor headers) {
        if (headers.getSessionId() == null
                || headers.getSubscriptionId() == null
                || headers.getSubscriptionId().isBlank()
                || headers.getSubscriptionId().length() > 255)
            throw new SubscriptionUnauthorizedException(
                    "Session and bounded subscription IDs are required");
    }

    private static JwtPrincipal principal(StompHeaderAccessor headers) {
        if (!(headers.getUser() instanceof JwtPrincipal principal))
            throw new SubscriptionUnauthorizedException("Authenticated session required");
        return principal;
    }
}

package com.example.omp.notification.websocket;

import com.example.omp.notification.config.NotificationProperties;
import com.example.omp.notification.subscription.SubscriptionRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Tracks actual sockets, including idle authenticated sessions without subscriptions. */
@Component
public class WebSocketSessions implements ChannelInterceptor {
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final Map<PendingKey, java.util.UUID> pending = new java.util.HashMap<>();

    private record PendingKey(String session, String subscription) {}

    private final SubscriptionRegistry registry;
    private final NotificationProperties properties;

    public WebSocketSessions(SubscriptionRegistry registry, NotificationProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public synchronized void opened(WebSocketSession session) throws java.io.IOException {
        if (sockets.size() >= properties.getMaxConcurrentClients()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sockets.put(session.getId(), session);
    }

    public synchronized void closed(String id) {
        pending.keySet().removeIf(key -> key.session().equals(id));
        sockets.remove(id);
        expiries.remove(id);
        registry.removeSession(id);
    }

    public synchronized java.util.UUID beginSubscription(String session, String subscription) {
        active(session);
        long waiting =
                pending.keySet().stream().filter(key -> key.session().equals(session)).count();
        if (registry.subscriptionsForSession(session) + waiting >= 100)
            throw new com.example.omp.notification.subscription.SubscriptionUnauthorizedException(
                    "Session subscription limit reached");
        var ticket = java.util.UUID.randomUUID();
        pending.put(new PendingKey(session, subscription), ticket);
        return ticket;
    }

    public synchronized void cancelPending(
            String session, String subscription, java.util.UUID ticket) {
        pending.remove(new PendingKey(session, subscription), ticket);
    }

    public synchronized void register(
            com.example.omp.notification.subscription.Subscription subscription,
            java.util.UUID ticket) {
        active(subscription.sessionId());
        if (!ticket.equals(
                pending.get(new PendingKey(subscription.sessionId(), subscription.id()))))
            throw new com.example.omp.notification.subscription.SubscriptionUnauthorizedException(
                    "Subscription was cancelled");
        registry.register(subscription);
        pending.remove(new PendingKey(subscription.sessionId(), subscription.id()));
    }

    public synchronized void unsubscribe(String session, String subscription) {
        pending.remove(new PendingKey(session, subscription));
        registry.unregister(session, subscription);
    }

    private void active(String id) {
        var socket = sockets.get(id);
        var expiry = expiries.get(id);
        if (socket == null || !socket.isOpen() || expiry == null || !Instant.now().isBefore(expiry))
            throw new com.example.omp.notification.subscription.SubscriptionUnauthorizedException(
                    "Session is closed or expired");
    }

    @EventListener
    public void connected(SessionConnectEvent event) {
        var headers = StompHeaderAccessor.wrap(event.getMessage());
        var attrs = headers.getSessionAttributes();
        if (attrs != null && attrs.get("jwtExpiresAt") instanceof Instant expiry)
            expiries.put(headers.getSessionId(), expiry);
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        closed(event.getSessionId());
    }

    @Scheduled(
            fixedDelayString = "${omp.notifications.expiry-check-ms:1000}",
            scheduler = "webSocketHeartbeatScheduler")
    public void expire() {
        Instant now = Instant.now();
        expiries.forEach(
                (id, expiry) -> {
                    if (!now.isBefore(expiry)) {
                        registry.removeSession(id);
                        var socket = sockets.get(id);
                        if (socket != null)
                            try {
                                socket.close(CloseStatus.POLICY_VIOLATION);
                            } catch (java.io.IOException ignored) {
                                /* closed below */
                            }
                        closed(id);
                    }
                });
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var h = SimpMessageHeaderAccessor.wrap(message);
        if (h.getMessageType() == SimpMessageType.MESSAGE) {
            Instant expiry = expiries.get(h.getSessionId());
            if (expiry == null || !Instant.now().isBefore(expiry)) return null;
        }
        return message;
    }

    public int size() {
        return sockets.size();
    }
}

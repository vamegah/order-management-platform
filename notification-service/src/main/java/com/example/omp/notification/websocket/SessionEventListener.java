package com.example.omp.notification.websocket;

import com.example.omp.notification.subscription.SubscriptionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Listens to Spring's WebSocket session lifecycle events and keeps the {@link SubscriptionRegistry}
 * in sync.
 *
 * <p>Events:
 *
 * <ul>
 *   <li>{@code SessionConnectedEvent} — a new STOMP session is established (post-CONNECT). We log
 *       and, in future phases, might record the connection in a metrics registry.
 *   <li>{@code SessionDisconnectEvent} — session closed. We remove all subscriptions for this
 *       session.
 *   <li>{@code SessionSubscribeEvent} — the framework has registered a subscription. Our {@link
 *       SubscriptionInterceptor} already handled it; this is for logging.
 *   <li>{@code SessionUnsubscribeEvent} — a subscription was removed. Same — we log but the
 *       SubscriptionInterceptor already removed the matching session/subscription ID.
 * </ul>
 */
@Component
public class SessionEventListener {

    private static final Logger log = LoggerFactory.getLogger(SessionEventListener.class);

    private final SubscriptionRegistry registry;

    public SessionEventListener(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info(
                "STOMP session connected: sessionId={} user={}",
                accessor.getSessionId(),
                accessor.getUser() != null ? accessor.getUser().getName() : "?");
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        registry.removeSession(sessionId);
        log.info("STOMP session disconnected: sessionId={}", sessionId);
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.debug(
                "Session subscribe (framework event): session={} dest={}",
                accessor.getSessionId(),
                accessor.getDestination());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.debug(
                "Session unsubscribe (framework event): session={} subId={}",
                accessor.getSessionId(),
                accessor.getSubscriptionId());
    }
}

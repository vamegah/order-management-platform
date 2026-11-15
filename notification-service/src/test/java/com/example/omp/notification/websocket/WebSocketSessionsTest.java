package com.example.omp.notification.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.config.NotificationProperties;
import com.example.omp.notification.subscription.SubscriptionRegistry;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionConnectEvent;

class WebSocketSessionsTest {
    final SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
    final NotificationProperties properties = new NotificationProperties();
    final WebSocketSessions sessions = new WebSocketSessions(registry, properties);

    void authenticate(String id, Instant expiry) {
        var h = StompHeaderAccessor.create(StompCommand.CONNECT);
        h.setSessionId(id);
        var attrs = new HashMap<String, Object>();
        attrs.put("jwtExpiresAt", expiry);
        h.setSessionAttributes(attrs);
        sessions.connected(
                new SessionConnectEvent(
                        this, MessageBuilder.createMessage(new byte[0], h.getMessageHeaders())));
    }

    @Test
    void idleExpiredSessionIsClosedAndItsSubscriptionsAreRemoved() throws Exception {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("idle");
        sessions.opened(socket);
        authenticate("idle", Instant.now().minusSeconds(1));
        sessions.expire();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(registry, atLeastOnce()).removeSession("idle");
        assertThat(sessions.size()).isZero();
    }

    @Test
    void currentSessionRemainsOpenUntilExpiryAndDisconnectClearsIt() throws Exception {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("active");
        sessions.opened(socket);
        authenticate("active", Instant.now().plusSeconds(30));
        sessions.expire();
        verify(socket, never()).close(any());
        sessions.closed("active");
        assertThat(sessions.size()).isZero();
        verify(registry).removeSession("active");
    }

    @Test
    void connectionLimitRejectsAdditionalSockets() throws Exception {
        properties.setMaxConcurrentClients(1);
        var first = mock(WebSocketSession.class);
        when(first.getId()).thenReturn("one");
        sessions.opened(first);
        var extra = mock(WebSocketSession.class);
        sessions.opened(extra);
        verify(extra).close(CloseStatus.POLICY_VIOLATION);
        assertThat(sessions.size()).isEqualTo(1);
    }

    @Test
    void expiredOrUnauthenticatedSessionsCannotReceiveAnotherEvent() {
        var h = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        h.setSessionId("recipient");
        var message = MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
        assertThat(sessions.preSend(message, null)).isNull();
        authenticate("recipient", Instant.now().plusSeconds(30));
        assertThat(sessions.preSend(message, null)).isSameAs(message);
        authenticate("recipient", Instant.now().minusSeconds(1));
        assertThat(sessions.preSend(message, null)).isNull();
    }
}

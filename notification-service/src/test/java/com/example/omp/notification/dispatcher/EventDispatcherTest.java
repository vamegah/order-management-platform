package com.example.omp.notification.dispatcher;

import static org.mockito.Mockito.*;

import com.example.omp.notification.subscription.*;
import com.example.omp.notification.websocket.JwtPrincipal;
import com.example.omp.shared.events.OrderEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class EventDispatcherTest {
    @Test
    void privateNotificationsReachOnlyMatchingCustomerAndOncePerUser() {
        var registry = new SubscriptionRegistry();
        var template = mock(SimpMessagingTemplate.class);
        var principal = new JwtPrincipal("owner-subject", "shared-name", null, Set.of("customer"));
        registry.register(
                Subscription.create(
                        "a",
                        "session-a",
                        "/user/queue/notifications",
                        TopicType.NOTIFICATIONS,
                        "owner",
                        principal));
        registry.register(
                Subscription.create(
                        "b",
                        "session-b",
                        "/user/queue/notifications",
                        TopicType.NOTIFICATIONS,
                        "owner",
                        principal));
        registry.register(
                Subscription.create(
                        "c",
                        "session-c",
                        "/user/queue/notifications",
                        TopicType.NOTIFICATIONS,
                        "other",
                        new JwtPrincipal(
                                "other-subject", "shared-name", null, Set.of("customer"))));
        var event =
                new OrderEvents.OrderPlaced(
                        "event",
                        "ORDER_PLACED",
                        Instant.now(),
                        1,
                        "order",
                        "ORD",
                        "owner",
                        "PLACED",
                        "actor");
        var dispatcher =
                new EventDispatcherImpl(
                        new DestinationResolver(), registry, template, new SimpleMeterRegistry());
        dispatcher.dispatch(event);
        verify(template)
                .convertAndSendToUser(
                        eq("owner-subject"),
                        eq("/queue/notifications"),
                        eq(EventEnvelope.from(event)));
        verifyNoMoreInteractions(template);
        registry.removeSession("session-a");
        registry.removeSession("session-b");
        dispatcher.dispatch(event);
        verifyNoMoreInteractions(template);
    }
}

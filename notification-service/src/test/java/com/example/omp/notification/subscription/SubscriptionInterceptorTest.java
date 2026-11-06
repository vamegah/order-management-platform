package com.example.omp.notification.subscription;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.websocket.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;

class SubscriptionInterceptorTest {
    private final SubscriptionRegistry registry = new SubscriptionRegistry();
    private final OwnershipVerifier ownership = mock(OwnershipVerifier.class);
    private final SubscriptionInterceptor interceptor =
            new SubscriptionInterceptor(registry, ownership);
    private final JwtPrincipal principal =
            new JwtPrincipal("subject", "name", null, Set.of("customer"));
    private final String payment = UUID.randomUUID().toString();

    private StompHeaderAccessor frame(
            StompCommand command, String session, String id, String destination) {
        var frame = StompHeaderAccessor.create(command);
        frame.setSessionId(session);
        frame.setSubscriptionId(id);
        frame.setDestination(destination);
        frame.setUser(principal);
        frame.setSessionAttributes(new HashMap<>(Map.of("jwt", new SessionToken("session-token"))));
        return frame;
    }

    private void send(StompHeaderAccessor frame) {
        interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], frame.getMessageHeaders()), null);
    }

    @Test
    void unsubscribeRemovesOnlyMatchingIdInMatchingSession() {
        String destination = "/topic/payments/" + payment;
        send(frame(StompCommand.SUBSCRIBE, "one", "a", destination));
        send(frame(StompCommand.SUBSCRIBE, "one", "b", destination));
        send(frame(StompCommand.SUBSCRIBE, "two", "a", destination));
        send(frame(StompCommand.UNSUBSCRIBE, "one", "a", null));
        assertThat(registry.totalSubscriptions()).isEqualTo(2);
        assertThat(registry.sessionsSnapshot().get("one"))
                .extracting(Subscription::id)
                .containsExactly("b");
        send(frame(StompCommand.DISCONNECT, "one", null, null));
        assertThat(registry.totalSubscriptions()).isEqualTo(1);
        assertThat(registry.totalSessions()).isEqualTo(1);
        send(frame(StompCommand.UNSUBSCRIBE, "two", "a", null));
        assertThat(registry.find(TopicType.PAYMENTS, payment)).isEmpty();
    }

    @Test
    void deniesUnverifiedSubscriptionsAndClearsRelayedTokenOnFailure() {
        doAnswer(
                        invocation -> {
                            assertThat(JwtThreadLocalHolder.get()).isEqualTo("session-token");
                            throw new SubscriptionUnauthorizedException("Denied");
                        })
                .when(ownership)
                .verifyPaymentOwnership(payment, principal);
        assertThatThrownBy(
                        () ->
                                send(
                                        frame(
                                                StompCommand.SUBSCRIBE,
                                                "one",
                                                "a",
                                                "/topic/payments/" + payment)))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        assertThat(registry.totalSubscriptions()).isZero();
        assertThat(JwtThreadLocalHolder.get()).isNull();
    }

    @Test
    void rejectsPublishingMissingIdsAndDuplicateIds() {
        assertThatThrownBy(
                        () ->
                                send(
                                        frame(
                                                StompCommand.SEND,
                                                "one",
                                                "a",
                                                "/topic/payments/" + payment)))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        assertThatThrownBy(
                        () ->
                                send(
                                        frame(
                                                StompCommand.SUBSCRIBE,
                                                "one",
                                                null,
                                                "/topic/payments/" + payment)))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        send(frame(StompCommand.SUBSCRIBE, "one", "a", "/topic/payments/" + payment));
        assertThatThrownBy(
                        () ->
                                send(
                                        frame(
                                                StompCommand.SUBSCRIBE,
                                                "one",
                                                "a",
                                                "/topic/payments/" + payment)))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        assertThat(registry.totalSubscriptions()).isEqualTo(1);
    }

    @Test
    void notificationQueueUsesResolvedCustomerInsteadOfClientChosenIdentity() {
        UUID customer = UUID.randomUUID();
        when(ownership.resolveCustomerId(principal)).thenReturn(customer);
        send(frame(StompCommand.SUBSCRIBE, "one", "notifications", "/user/queue/notifications"));
        assertThat(registry.find(TopicType.NOTIFICATIONS, customer.toString())).hasSize(1);
        assertThatThrownBy(
                        () ->
                                send(
                                        frame(
                                                StompCommand.SUBSCRIBE,
                                                "one",
                                                "other",
                                                "/user/victim/queue/notifications")))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
    }
}

package com.example.omp.notification.websocket;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.shared.web.quota.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;

class StompQuotaInterceptorTest {
    private StompQuotaInterceptor interceptor(QuotaStore store) {
        return new StompQuotaInterceptor(
                new QuotaService(
                        store,
                        new QuotaProperties(
                                true,
                                "notification",
                                "localhost",
                                6379,
                                "",
                                null,
                                false,
                                Duration.ofMillis(200),
                                8192,
                                1),
                        new SimpleMeterRegistry()));
    }

    private Message<byte[]> message(StompCommand command, boolean authenticated) {
        var headers = StompHeaderAccessor.create(command);
        if (authenticated)
            headers.setUser(new JwtPrincipal("verified-subject", "display-name", "", Set.of()));
        headers.setSessionAttributes(
                new HashMap<>(Map.of("quotaIssuer", "https://issuer.example")));
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test
    void authenticatedConnectAndSubscribeShareTheirOwnPolicyAcrossSessions() {
        var keys = new ArrayList<String>();
        var interceptor =
                interceptor(
                        (key, capacity) -> {
                            keys.add(key);
                            return 0;
                        });
        for (var command : List.of(StompCommand.CONNECT, StompCommand.SUBSCRIBE)) {
            var message = message(command, true);
            assertThat(interceptor.preSend(message, null)).isSameAs(message);
        }
        assertThat(keys).hasSize(2);
        assertThat(keys.getFirst()).contains("CONNECT").doesNotContain("subject");
        assertThat(keys.getLast()).contains("SUBSCRIBE").doesNotContain("issuer");
    }

    @Test
    void unsubscribeDisconnectAndHeartbeatNeverPreventCleanup() {
        var interceptor =
                interceptor(
                        (key, capacity) -> {
                            throw new AssertionError("Cleanup cannot consume a quota");
                        });
        for (var command :
                List.of(StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT, StompCommand.SEND)) {
            var message = message(command, true);
            assertThat(interceptor.preSend(message, null)).isSameAs(message);
        }
        var message = MessageBuilder.withPayload(new byte[0]).build();
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        var unauthenticated = message(StompCommand.CONNECT, false);
        assertThat(interceptor.preSend(unauthenticated, null)).isSameAs(unauthenticated);
    }

    @Test
    void rejectionEmitsStableCodeAndRetryAfterWithoutNativeCredentials() {
        var interceptor = interceptor((key, capacity) -> 1234);
        var message = message(StompCommand.SUBSCRIBE, true);
        var rejection =
                catchThrowableOfType(
                        () -> interceptor.preSend(message, null),
                        StompQuotaInterceptor.Rejected.class);
        assertThat(rejection.code()).isEqualTo("RATE_LIMITED");
        var reply =
                new StompErrorHandler()
                        .handleClientMessageProcessingError(
                                message, new MessageDeliveryException(message, rejection));
        var headers = StompHeaderAccessor.wrap(reply);
        assertThat(headers.getMessage()).isEqualTo("RATE_LIMITED");
        assertThat(headers.getFirstNativeHeader("retry-after")).isEqualTo("2");
        assertThat(reply.getPayload()).isEmpty();
    }

    @Test
    void unavailableStoreRejectsBeforeOwnershipRequestsWithRedactedCode() {
        var interceptor =
                interceptor(
                        (key, capacity) -> {
                            throw new IllegalStateException("private secret");
                        });
        var message = message(StompCommand.CONNECT, true);
        var failure = catchThrowable(() -> interceptor.preSend(message, null));
        var reply = new StompErrorHandler().handleClientMessageProcessingError(message, failure);
        assertThat(StompHeaderAccessor.wrap(reply).getMessage()).isEqualTo("QUOTA_UNAVAILABLE");
        assertThat(new String(reply.getPayload())).doesNotContain("private");
    }
}

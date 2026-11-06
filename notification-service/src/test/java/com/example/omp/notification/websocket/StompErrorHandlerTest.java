package com.example.omp.notification.websocket;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.notification.subscription.SubscriptionUnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;

class StompErrorHandlerTest {
    @Test
    void interceptorFailuresHaveStableCodesWithoutCredentialsOrRequestHeaders() {
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.addNativeHeader("Authorization", "Bearer secret-token");
        var request = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        var handler = new StompErrorHandler();
        for (var failure :
                new RuntimeException[] {
                    new BadCredentialsException("secret-token"),
                    new SubscriptionUnauthorizedException("private-resource"),
                    new IllegalStateException("private-backend-message")
                }) {
            var result =
                    handler.handleClientMessageProcessingError(
                            request, new MessageDeliveryException(request, failure));
            var accessor = StompHeaderAccessor.wrap(result);
            assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
            assertThat(accessor.getMessage())
                    .isEqualTo(
                            failure instanceof BadCredentialsException
                                    ? "UNAUTHENTICATED"
                                    : failure instanceof SubscriptionUnauthorizedException
                                            ? "UNAUTHORIZED"
                                            : "INTERNAL");
            assertThat(accessor.getNativeHeader("Authorization")).isNull();
            assertThat(result.getPayload()).isEmpty();
            assertThat(accessor.toNativeHeaderMap().toString())
                    .doesNotContain("secret-token", "private-resource", "private-backend-message");
        }
    }
}

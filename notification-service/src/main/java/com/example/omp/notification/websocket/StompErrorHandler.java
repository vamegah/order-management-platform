package com.example.omp.notification.websocket;

import com.example.omp.notification.subscription.SubscriptionUnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/** Channel-interceptor failures bypass controller advice. Return only stable, redacted codes. */
public final class StompErrorHandler extends StompSubProtocolErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(StompErrorHandler.class);

    @Override
    public Message<byte[]> handleClientMessageProcessingError(
            Message<byte[]> message, Throwable failure) {
        String code = "INTERNAL";
        Throwable cause = failure;
        Throwable deepest = failure;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            deepest = cause;
            if (cause instanceof AuthenticationException) {
                code = "UNAUTHENTICATED";
                break;
            }
            if (cause instanceof SubscriptionUnauthorizedException) {
                code = "UNAUTHORIZED";
                break;
            }
        }
        // Exception messages/frames can contain native credentials; log only the type and code.
        log.warn(
                "STOMP frame rejected: code={} cause={}", code, deepest.getClass().getSimpleName());
        var headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setMessage(code);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}

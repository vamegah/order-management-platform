package com.example.omp.notification.websocket;

import com.example.omp.notification.subscription.SubscriptionUnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * Handles exceptions thrown during STOMP message handling.
 *
 * <p>Converts Java exceptions into structured STOMP ERROR frames delivered to the specific user
 * whose message triggered the exception.
 *
 * <p><b>Error codes:</b> the same vocabulary as GraphQL:
 *
 * <ul>
 *   <li>{@code UNAUTHENTICATED} — missing or invalid token (handled at CONNECT).
 *   <li>{@code UNAUTHORIZED} — valid token, insufficient permission.
 *   <li>{@code NOT_FOUND} — subscribed resource doesn't exist.
 *   <li>{@code INTERNAL} — unexpected error.
 * </ul>
 *
 * <p><b>Delivery:</b> {@code @SendToUser} sends the error only to the session that triggered the
 * exception (not broadcast). The {@code broadcast=false} flag is the default; we set it explicitly
 * for clarity.
 */
@ControllerAdvice
public class WebSocketExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketExceptionHandler.class);

    @MessageExceptionHandler(SubscriptionUnauthorizedException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public StompError handleUnauthorized(SubscriptionUnauthorizedException ex) {
        log.warn("Subscription unauthorized: {}", ex.getMessage());
        return new StompError("UNAUTHORIZED", ex.getMessage());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public StompError handleGeneric(Exception ex) {
        log.error("Unexpected WebSocket error", ex);
        return new StompError("INTERNAL", "An unexpected error occurred");
    }

    /** Structured error payload sent to the client over STOMP. */
    public record StompError(String code, String message) {}
}

package com.example.omp.notification.clients;

import com.example.omp.notification.websocket.JwtThreadLocalHolder;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Feign configuration for outbound calls from notification-service.
 *
 * <p>Two interceptors:
 *
 * <ul>
 *   <li><b>Auth forwarding</b> — the client's JWT is stored in a thread-local (or Spring's
 *       SecurityContext via the STOMP principal) during subscription handling. This interceptor
 *       copies it into the {@code Authorization} header on outbound calls.
 *   <li><b>Request ID</b> — propagates the request ID from MDC.
 * </ul>
 *
 * <p><b>Challenge:</b> unlike REST services, the "current request" in notification-service is a
 * WebSocket session, not an HTTP request. The SecurityContext isn't automatically populated for
 * outbound calls made during message handling.
 *
 * <p><b>Solution:</b> the WebSocket handler stores the JWT on the STOMP session, and the ownership
 * verification code retrieves it and stores it in a thread-local before calling Feign. See {@link
 * JwtThreadLocalHolder}.
 */
@Configuration
public class NotificationFeignConfig {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";

    @Bean
    public RequestInterceptor notificationAuthForwardingInterceptor() {
        return template -> {
            String sessionJwt = JwtThreadLocalHolder.get();
            if (sessionJwt != null) {
                template.header("Authorization", "Bearer " + sessionJwt);
                return;
            }
            // Outside STOMP handling, use the current HTTP caller's security context.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                template.header("Authorization", "Bearer " + jwtAuth.getToken().getTokenValue());
                return;
            }
        };
    }

    @Bean
    public RequestInterceptor notificationRequestIdInterceptor() {
        return template -> {
            String requestId = MDC.get(MDC_REQUEST_ID);
            if (requestId != null && !requestId.isBlank()) {
                template.header(REQUEST_ID_HEADER, requestId);
            }
        };
    }
}

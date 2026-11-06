package com.example.omp.gateway.config;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Feign configuration that forwards authentication and correlation context on outbound calls.
 *
 * <p>Three headers are propagated:
 *
 * <ul>
 *   <li>{@code Authorization: Bearer <jwt>} — extracted from the current SecurityContext. Without
 *       it, downstream services reject the call.
 *   <li>{@code X-Request-Id} — from MDC, preserves request correlation across services in logs.
 *   <li>{@code traceparent} — added automatically by Micrometer Tracing's Feign integration
 *       (requires the tracing bridge, which the pom includes).
 * </ul>
 */
@Configuration
public class FeignAuthForwardingConfig {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";

    @Bean
    public RequestInterceptor authForwardingInterceptor() {
        return template -> {
            // Propagate the JWT from the current security context.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                template.header("Authorization", "Bearer " + jwtAuth.getToken().getTokenValue());
            }

            // Propagate the request id from MDC.
            String requestId = MDC.get(MDC_REQUEST_ID);
            if (requestId != null && !requestId.isBlank()) {
                template.header(REQUEST_ID_HEADER, requestId);
            }

            // traceparent is added by Micrometer Tracing's Feign interceptor,
            // registered automatically by spring-cloud-sleuth-like behavior in
            // Micrometer Tracing 1.3+. No action needed here.
        };
    }
}

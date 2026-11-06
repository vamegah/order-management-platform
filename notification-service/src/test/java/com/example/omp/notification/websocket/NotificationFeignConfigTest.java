package com.example.omp.notification.websocket;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.notification.clients.NotificationFeignConfig;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class NotificationFeignConfigTest {
    @Test
    void stompCallerTokenTakesPriorityOverAnUnrelatedThreadSecurityContext() {
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new JwtAuthenticationToken(
                                    Jwt.withTokenValue("unrelated-token")
                                            .header("alg", "none")
                                            .subject("other")
                                            .build()));
            JwtThreadLocalHolder.set("session-token");
            var request = new RequestTemplate();
            new NotificationFeignConfig().notificationAuthForwardingInterceptor().apply(request);
            assertThat(request.headers().get("Authorization"))
                    .containsExactly("Bearer session-token");
        } finally {
            JwtThreadLocalHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}

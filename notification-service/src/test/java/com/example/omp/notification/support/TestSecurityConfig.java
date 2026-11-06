package com.example.omp.notification.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return token -> {
            if (TestTokens.alice().equals(token)) {
                return build(TestTokens.ALICE_CUSTOMER_ID, "alice", List.of("customer"));
            }
            if (TestTokens.admin().equals(token)) {
                return build(TestTokens.ADMIN_ID, "admin", List.of("customer", "admin"));
            }
            if (TestTokens.bob().equals(token)) {
                return build(TestTokens.BOB_CUSTOMER_ID, "bob", List.of("customer"));
            }
            throw new BadJwtException("Unknown test token");
        };
    }

    private static Jwt build(UUID subject, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token-" + username)
                .header("alg", "none")
                .subject(subject.toString())
                .claim("preferred_username", username)
                .claim("email", username + "@omp.local")
                .claim("customer_id", subject.toString())
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
    }
}

package com.example.omp.customer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test configuration that replaces the real JWT decoder with a mock.
 *
 * <p>Provides two fixed tokens:
 *
 * <ul>
 *   <li>{@code aliceToken()} — a JWT with the {@code customer} role.
 *   <li>{@code adminToken()} — a JWT with both {@code customer} and {@code admin} roles.
 * </ul>
 *
 * <p>The decoder recognizes these tokens by their {@code sub} claim and returns the appropriate
 * JWT. Any other token → 401.
 */
@TestConfiguration
public class TestSecurityConfig {

    private static final String ALICE_SUB = "test-alice";
    private static final String ADMIN_SUB = "test-admin";

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return token -> {
            if (aliceToken().equals(token)) {
                return buildJwt(ALICE_SUB, "alice", List.of("customer"));
            }
            if (adminToken().equals(token)) {
                return buildJwt(ADMIN_SUB, "admin", List.of("customer", "admin"));
            }
            if (linkedAliceToken().equals(token)) {
                Jwt original = buildJwt(ALICE_SUB, "alice", List.of("customer"));
                return Jwt.withTokenValue(token)
                        .headers(headers -> headers.putAll(original.getHeaders()))
                        .claims(claims -> claims.putAll(original.getClaims()))
                        .claim("customer_id", "00000000-0000-0000-0000-000000000101")
                        .build();
            }
            if (unlinkedToken().equals(token)) {
                return buildJwt("test-unlinked", "unlinked", List.of("customer"));
            }
            throw new BadJwtException("Unknown test token");
        };
    }

    public static String aliceToken() {
        return "test-token-alice";
    }

    public static String adminToken() {
        return "test-token-admin";
    }

    public static String linkedAliceToken() {
        return "test-token-alice-linked";
    }

    public static String unlinkedToken() {
        return "test-token-unlinked";
    }

    private static Jwt buildJwt(String sub, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token-" + username)
                .header("alg", "none")
                .subject(sub)
                .claim("preferred_username", username)
                .claim("email", username + "@omp.local")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
    }
}

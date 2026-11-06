package com.example.omp.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
            // The token value itself encodes the user; tests pass
            // TestSecurityConfig.aliceToken() as the Bearer token.
            if (aliceToken().equals(token)) {
                return buildJwt(ALICE_SUB, "alice", List.of("customer"));
            }
            if (adminToken().equals(token)) {
                return buildJwt(ADMIN_SUB, "admin", List.of("customer", "admin"));
            }
            if (legacyToken().equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(ALICE_SUB)
                        .claim("realm_access", Map.of("roles", List.of("customer")))
                        .build();
            }
            throw new org.springframework.security.oauth2.jwt.BadJwtException("Unknown test token");
        };
    }

    /** Returns the fixed JWT string for Alice. */
    public static String aliceToken() {
        return "test-token-alice";
    }

    /** Returns the fixed JWT string for Admin. */
    public static String adminToken() {
        return "test-token-admin";
    }

    public static String legacyToken() {
        return "test-token-without-customer-claim";
    }

    private static Jwt buildJwt(String sub, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token-" + username)
                .header("alg", "none")
                .subject(sub)
                .claim("customer_id", "00000000-0000-0000-0000-000000000101")
                .claim("preferred_username", username)
                .claim("email", username + "@omp.local")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
    }
}

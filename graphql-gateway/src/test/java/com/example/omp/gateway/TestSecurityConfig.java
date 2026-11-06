package com.example.omp.gateway;

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
 * <p>Two fixed tokens are recognized:
 *
 * <ul>
 *   <li>{@link #aliceToken()} — a JWT with the {@code customer} role.
 *   <li>{@link #adminToken()} — a JWT with {@code customer} and {@code admin}.
 * </ul>
 *
 * <p>Any other token → {@link JwtException}, which Spring Security translates into a 401 response.
 *
 * <p><b>Why not generate real signed JWTs?</b> Because the gateway's tests are not validating
 * signature verification (that's Spring Security's job, and it's tested by the framework). The
 * tests focus on authorization rules and resolver behavior. A mock decoder isolates those concerns.
 *
 * <p><b>Difference from Phase 1's TestSecurityConfig:</b> the gateway doesn't need a {@code
 * customer_id} claim — it forwards the token to downstream services, which apply their own
 * ownership logic. The mock tokens here carry only what the gateway itself inspects.
 */
@TestConfiguration
public class TestSecurityConfig {

    private static final String ALICE_TOKEN = "test-token-alice";
    private static final String ADMIN_TOKEN = "test-token-admin";

    /**
     * Returns Alice's fixed test token. Use with REST Assured:
     *
     * <pre>{@code
     * given().auth().oauth2(TestSecurityConfig.aliceToken())...
     * }</pre>
     */
    public static String aliceToken() {
        return ALICE_TOKEN;
    }

    /** Returns Admin's fixed test token. */
    public static String adminToken() {
        return ADMIN_TOKEN;
    }

    /**
     * A mock {@link JwtDecoder} bean that recognizes the two fixed tokens. Marked {@code @Primary}
     * to override the real decoder configured by the OAuth2 Resource Server starter.
     */
    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return token -> {
            if (ALICE_TOKEN.equals(token)) {
                return buildJwt("test-alice", "alice", List.of("customer"));
            }
            if (ADMIN_TOKEN.equals(token)) {
                return buildJwt("test-admin", "admin", List.of("customer", "admin"));
            }
            throw new BadJwtException("Unknown test token");
        };
    }

    // -------------------------------------------------------------------------
    // JWT construction
    // -------------------------------------------------------------------------

    private static Jwt buildJwt(String sub, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token-" + username)
                .header("alg", "none")
                .subject(sub)
                .claim("preferred_username", username)
                .claim("email", username + "@omp.local")
                // Keycloak realm roles — same structure the real converter expects.
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
    }
}

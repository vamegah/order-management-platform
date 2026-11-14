package com.example.omp.shared.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class GrpcJwtAuthenticationTest {
    Jwt token(Object realm) {
        var builder =
                Jwt.withTokenValue("verified-fixture")
                        .header("alg", "RS256")
                        .subject("subject")
                        .claim("scope", "openid order.reconcile");
        if (realm != null) builder.claim("realm_access", realm);
        return builder.build();
    }

    @Test
    void combinesVerifiedRealmRolesWithScopesAndRetainsTheSubject() {
        var authentication =
                GrpcJwtAuthentication.converter()
                        .convert(token(Map.of("roles", List.of("admin", "admin", "user"))));
        assertThat(authentication.getName()).isEqualTo("subject");
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        "SCOPE_openid",
                        "SCOPE_order.reconcile",
                        "ROLE_admin",
                        "ROLE_user",
                        "FACTOR_BEARER");
    }

    @Test
    void malformedOrMissingRoleClaimsNeverGrantAnAdministratorRole() {
        for (Object realm :
                Arrays.asList(
                        null,
                        "admin",
                        Map.of(),
                        Map.of("roles", "admin"),
                        Map.of("roles", List.of(1, "", "bad role")))) {
            var authentication = GrpcJwtAuthentication.converter().convert(token(realm));
            assertThat(authentication.getAuthorities())
                    .extracting("authority")
                    .containsExactlyInAnyOrder(
                            "SCOPE_openid", "SCOPE_order.reconcile", "FACTOR_BEARER");
        }
    }
}

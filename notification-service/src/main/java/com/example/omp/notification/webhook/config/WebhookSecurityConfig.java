package com.example.omp.notification.webhook.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security rules for /api/v1/webhook-*.
 *
 * <p>Authentication: JWT bearer (validated by the resource server config from Batch 8.2 — this
 * chain only specifies authorization rules).
 *
 * <p>Authorization: - /api/v1/webhook-subscriptions/** : authenticated (owner enforced in the
 * service layer) - /api/v1/webhook-deliveries/** : authenticated (owner enforced in the service
 * layer) - /api/v1/webhook-dlq/** : ROLE_ADMIN only
 */
@Configuration
public class WebhookSecurityConfig {

    @Bean
    @Order(10)
    public SecurityFilterChain webhookSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        "/api/v1/webhook-subscriptions/**",
                        "/api/v1/webhook-deliveries/**",
                        "/api/v1/webhook-dlq/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/api/v1/webhook-dlq/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(
                                        jwt ->
                                                jwt.jwtAuthenticationConverter(
                                                        jwtAuthenticationConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        // Map realm_access.roles -> ROLE_* authorities (Keycloak convention).
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    var realmAccess = jwt.getClaimAsMap("realm_access");
                    if (realmAccess == null
                            || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
                        return List.of();
                    }
                    return roles.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .map(
                                    role ->
                                            (GrantedAuthority)
                                                    new SimpleGrantedAuthority(
                                                            "ROLE_"
                                                                    + role.toUpperCase(
                                                                            Locale.ROOT)))
                            .toList();
                });
        return converter;
    }
}

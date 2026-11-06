package com.example.omp.product.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * <p>JWT validation is handled by the OAuth2 Resource Server starter, which fetches public keys
 * from Keycloak's JWKS endpoint (configured in application.yml via {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri}).
 *
 * <p>Authorization rules:
 *
 * <ul>
 *   <li>Public: {@code /actuator/health}, {@code /actuator/info}, OpenAPI docs and Swagger UI.
 *   <li>Any other endpoint: requires a valid JWT.
 *   <li>{@code POST}, {@code PUT}, {@code DELETE}: require the {@code admin} role (Keycloak realm
 *       role).
 * </ul>
 *
 * <p>Role extraction: Keycloak embeds realm roles in {@code realm_access.roles} of the JWT. We
 * translate each to a Spring Security {@code ROLE_<name>} authority so
 * {@code @PreAuthorize("hasRole('admin')")} works.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Main security filter chain.
     *
     * <p>Session is stateless: each request carries a JWT. No cookies, no server-side session. This
     * is required for horizontal scaling and is the standard pattern for a REST API.
     *
     * <p>CSRF is disabled: CSRF protection requires cookies, and we have none. The JWT is presented
     * on every request via the {@code Authorization: Bearer ...} header.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // Public: health and info (used by Docker and monitoring)
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info")
                                        .permitAll()

                                        // Public: OpenAPI spec and Swagger UI (in dev; disabled in
                                        // prod)
                                        .requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()

                                        // Protected: Prometheus metrics (internal-only via Traefik)
                                        .requestMatchers("/actuator/prometheus")
                                        .authenticated()

                                        // Reads: any authenticated user
                                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                                        .authenticated()

                                        // Writes: admin role only
                                        .requestMatchers(HttpMethod.POST, "/api/v1/**")
                                        .hasRole("admin")
                                        .requestMatchers(HttpMethod.PUT, "/api/v1/**")
                                        .hasRole("admin")
                                        .requestMatchers(HttpMethod.PATCH, "/api/v1/**")
                                        .hasRole("admin")
                                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**")
                                        .hasRole("admin")

                                        // Everything else: authenticated
                                        .anyRequest()
                                        .authenticated())

                // JWT validation (resource server)
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(
                                        jwt ->
                                                jwt.jwtAuthenticationConverter(
                                                        jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * Converts a Keycloak JWT to a Spring Security authentication token, extracting realm roles
     * from {@code realm_access.roles}.
     */
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * Extracts realm roles from Keycloak JWTs.
     *
     * <p>Keycloak places realm roles under {@code realm_access.roles}. We prefix them with {@code
     * ROLE_} so {@code hasRole('admin')} in {@code @PreAuthorize} works.
     */
    static class KeycloakRealmRoleConverter
            implements Converter<Jwt, Collection<GrantedAuthority>> {

        private final JwtGrantedAuthoritiesConverter defaultConverter =
                new JwtGrantedAuthoritiesConverter();

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<GrantedAuthority> authorities = new HashSet<>(defaultConverter.convert(jwt));

            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                authorities.addAll(
                        ((List<String>) roles)
                                .stream()
                                        .map(
                                                role ->
                                                        (GrantedAuthority)
                                                                new SimpleGrantedAuthority(
                                                                        "ROLE_" + role))
                                        .collect(Collectors.toSet()));
            }

            return authorities;
        }
    }
}

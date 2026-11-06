package com.example.omp.notification.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
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
 * Spring Security configuration for the HTTP layer.
 *
 * <p><b>Scope of this class:</b> HTTP endpoints (the WebSocket handshake endpoint and the
 * management endpoints). WebSocket <em>message-level</em> security (STOMP CONNECT validation) is
 * configured separately in Batch 8.3 via a {@code ChannelInterceptor}.
 *
 * <p><b>HTTP rules:</b>
 *
 * <ul>
 *   <li>{@code /ws/**} — the WebSocket handshake. Public at the HTTP layer; authentication happens
 *       at STOMP CONNECT.
 *   <li>{@code /actuator/health}, {@code /actuator/info} — public.
 *   <li>{@code /actuator/prometheus} — authenticated.
 *   <li>Any other endpoint — authenticated.
 * </ul>
 *
 * <p>The role converter is the same as in other services: Keycloak realm roles are extracted from
 * the JWT and prefixed with {@code ROLE_}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // WebSocket handshake endpoint — public at HTTP layer.
                                        // Authentication happens on STOMP CONNECT.
                                        .requestMatchers("/ws", "/ws/**")
                                        .permitAll()

                                        // Actuator health/info — public.
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info")
                                        .permitAll()

                                        // Actuator prometheus — authenticated.
                                        .requestMatchers("/actuator/prometheus")
                                        .authenticated()

                                        // Everything else — authenticated.
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

    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

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

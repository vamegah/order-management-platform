package com.example.omp.adapter.config;

import java.util.Collection;
import java.util.HashSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Banking account ownership is not modeled; the facade is an administrator API. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class BankingSecurityConfig {
    @Bean
    public SecurityFilterChain bankingSecurityFilterChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        var scopes = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    var authorities = new HashSet<GrantedAuthority>(scopes.convert(jwt));
                    var realm = jwt.getClaimAsMap("realm_access");
                    if (realm != null && realm.get("roles") instanceof Collection<?> roles) {
                        roles.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .filter(role -> !role.isBlank())
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .forEach(authorities::add);
                    }
                    return authorities;
                });
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info",
                                                "/error",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        .anyRequest()
                                        .hasRole("admin"))
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}

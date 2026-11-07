package com.example.omp.payment.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for payment-service.
 *
 * <p>Rules: - /api/v1/payments/webhooks/** : permitAll (authenticated by PSP HMAC) -
 * /actuator/health, /actuator/info : permitAll - everything else : JWT
 *
 * <p>Note: the webhook path is served by StripeWebhookController; that controller verifies the PSP
 * signature itself. The endpoint must be reachable by the PSP without a JWT.
 */
@Configuration
public class PaymentSecurityConfig {

    @Bean
    @Order(5)
    public SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/payments/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(10)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}

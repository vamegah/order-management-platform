package com.example.omp.payment.config;

import java.util.List;
import net.devh.boot.grpc.server.security.authentication.BearerAuthenticationReader;
import net.devh.boot.grpc.server.security.authentication.CompositeGrpcAuthenticationReader;
import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * Configures gRPC authentication.
 *
 * <p>There is no HTTP security filter chain here — the service has no REST endpoints.
 * Authentication happens via gRPC interceptors:
 *
 * <ol>
 *   <li>The {@code GrpcAuthenticationReader} extracts the JWT from gRPC metadata (the {@code
 *       Authorization: Bearer <token>} header).
 *   <li>The explicitly registered {@code AuthenticationManager} validates the JWT using the
 *       decoder.
 *   <li>The authenticated {@code Authentication} object is stored in the gRPC call's security
 *       context.
 * </ol>
 *
 * <p><b>Authorization:</b> service methods can use Spring Security's {@code @PreAuthorize} or check
 * the security context manually. Every business RPC requires a bearer identity. Shared refund and
 * recovery services apply their additional owner/administrator/scope policies.
 */
@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public org.springframework.security.web.SecurityFilterChain httpSecurity(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(
                                        org.springframework.security.config.http
                                                .SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/v1/payments/webhooks/*")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        resource ->
                                resource.jwt(
                                        org.springframework.security.config.Customizer
                                                .withDefaults()))
                .build();
    }

    /**
     * The JWT decoder, provided by the OAuth2 resource server starter. Validates tokens against
     * Keycloak's JWKS endpoint.
     *
     * <p>This bean is also used by the gRPC authentication reader below.
     */
    @Bean
    public JwtAuthenticationProvider jwtAuthenticationProvider(JwtDecoder jwtDecoder) {
        return new JwtAuthenticationProvider(jwtDecoder);
    }

    /**
     * Extracts the JWT from gRPC metadata. The metadata key is {@code Authorization}, value is
     * {@code Bearer <jwt>}.
     *
     * <p>grpc-spring-boot-starter's default reader is {@link BearerAuthenticationReader}, which
     * handles the Bearer prefix automatically.
     *
     * <p>The {@code CompositeGrpcAuthenticationReader} lets us chain multiple readers. For Phase 3,
     * we use only JWT.
     */
    @Bean
    public org.springframework.security.authentication.AuthenticationManager
            grpcAuthenticationManager(JwtAuthenticationProvider provider) {
        return new org.springframework.security.authentication.ProviderManager(provider);
    }

    @Bean
    public GrpcAuthenticationReader grpcAuthenticationReader() {
        return new CompositeGrpcAuthenticationReader(
                List.of(
                        new BearerAuthenticationReader(
                                org.springframework.security.oauth2.server.resource.authentication
                                                .BearerTokenAuthenticationToken
                                        ::new),
                        (call, headers) -> {
                            if (call.getMethodDescriptor()
                                    .getServiceName()
                                    .equals(
                                            com.example.omp.proto.payment.PaymentServiceGrpc
                                                    .SERVICE_NAME)) {
                                throw new org.springframework.security.authentication
                                        .BadCredentialsException("Bearer authentication required");
                            }
                            // Infrastructure health probes do not invoke business RPCs.
                            return null;
                        }));
    }
}

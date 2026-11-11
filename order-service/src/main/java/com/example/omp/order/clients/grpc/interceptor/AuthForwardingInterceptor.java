package com.example.omp.order.clients.grpc.interceptor;

import io.grpc.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * gRPC client interceptor that propagates the caller's JWT to downstream services.
 *
 * <p>Same "token relay" pattern as Phase 1's Feign interceptor:
 *
 * <ul>
 *   <li>The user authenticates with order-service via JWT.
 *   <li>Order-service extracts the JWT from the security context.
 *   <li>Every outbound gRPC call includes the JWT in the {@code Authorization} metadata header.
 *   <li>Downstream services validate the JWT against Keycloak.
 * </ul>
 *
 * <p><b>Why relay the user's token (not a service token)?</b> Because downstream services can then
 * apply their own per-user authorization. A service-account approach would lose this — downstream
 * services would see "order-service" instead of "alice".
 *
 * <p>A narrowly scoped workload identity takes precedence only while trusted order-owned work has
 * explicitly opened that scope. Otherwise the caller's JWT is relayed. No credential is retained
 * after the scope exits.
 *
 * <p><b>Registration:</b> added to every gRPC client via {@link
 * com.example.omp.order.config.GrpcClientConfig}.
 */
@Component
public class AuthForwardingInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String serviceToken = ServiceTokenScope.current();
                if (serviceToken != null) {
                    headers.put(AUTHORIZATION, "Bearer " + serviceToken);
                } else if (auth instanceof JwtAuthenticationToken jwtAuth) {
                    headers.put(AUTHORIZATION, "Bearer " + jwtAuth.getToken().getTokenValue());
                }
                super.start(responseListener, headers);
            }
        };
    }
}

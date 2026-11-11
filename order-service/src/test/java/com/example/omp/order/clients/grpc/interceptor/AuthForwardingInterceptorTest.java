package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.proto.inventory.*;
import io.grpc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthForwardingInterceptorTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private Metadata headers() {
        Channel channel = mock(Channel.class);
        ClientCall<GetStockLevelRequest, StockLevel> call = mock(ClientCall.class);
        when(channel.<GetStockLevelRequest, StockLevel>newCall(any(), any())).thenReturn(call);
        Metadata metadata = new Metadata();
        new AuthForwardingInterceptor()
                .interceptCall(
                        InventoryServiceGrpc.getGetStockLevelMethod(), CallOptions.DEFAULT, channel)
                .start(new ClientCall.Listener<>() {}, metadata);
        return metadata;
    }

    private String bearer() {
        return headers().get(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER));
    }

    @Test
    void workerIdentityIsScopedAndRedacted() {
        try (var scope = ServiceTokenScope.open("service-token")) {
            assertThat(bearer()).isEqualTo("Bearer service-token");
            assertThat(scope.toString()).doesNotContain("service-token");
        }
        assertThat(bearer()).isNull();
    }

    @Test
    void explicitlyScopedWorkerIdentityTakesPrecedenceOverCaller() {
        var jwt =
                Jwt.withTokenValue("user-token").header("alg", "RS256").subject("customer").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try (var scope = ServiceTokenScope.open("service-token")) {
            assertThat(bearer()).isEqualTo("Bearer service-token");
        }
        assertThat(bearer()).isEqualTo("Bearer user-token");
    }

    @Test
    void relaysCallerIdentityWhenNoWorkerScopeIsOpen() {
        var jwt =
                Jwt.withTokenValue("user-token").header("alg", "RS256").subject("customer").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertThat(bearer()).isEqualTo("Bearer user-token");
    }
}

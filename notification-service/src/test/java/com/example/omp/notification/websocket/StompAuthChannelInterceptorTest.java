package com.example.omp.notification.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.*;

class StompAuthChannelInterceptorTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(decoder);

    private void send(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
    }

    @Test
    void connectBindsVerifiedIdentityAndStoresTokenForRelay() {
        UUID customer = UUID.randomUUID();
        var jwt =
                Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject("stable-subject")
                        .claim("preferred_username", "mutable-name")
                        .claim("customer_id", customer.toString())
                        .claim("realm_access", Map.of("roles", List.of("customer")))
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        when(decoder.decode("token")).thenReturn(jwt);
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer token");
        accessor.setSessionAttributes(new HashMap<>());
        send(accessor);
        var principal = (JwtPrincipal) accessor.getUser();
        assertThat(principal.getName()).isEqualTo("stable-subject");
        assertThat(principal.customerId()).isEqualTo(customer);
        assertThat(accessor.getNativeHeader("Authorization")).isNull();
        assertThat(accessor.getSessionAttributes().toString()).doesNotContain("jwt=token");
        assertThat(accessor.getSessionAttributes())
                .containsEntry("jwt", new SessionToken("token"))
                .containsEntry("jwtExpiresAt", jwt.getExpiresAt());
    }

    @Test
    void invalidTokenDoesNotExposeDecoderDetails() {
        when(decoder.decode("bad")).thenThrow(new JwtException("internal validation details"));
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer bad");
        assertThatThrownBy(() -> send(accessor))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid JWT");
        assertThat(accessor.getUser()).isNull();
    }

    @Test
    void missingAuthenticationOrExpiredSessionCannotSubscribe() {
        var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        assertThatThrownBy(() -> send(accessor)).isInstanceOf(BadCredentialsException.class);
        accessor.setUser(new JwtPrincipal("subject", "name", null, Set.of()));
        accessor.setSessionAttributes(
                new HashMap<>(Map.of("jwtExpiresAt", Instant.now().minusSeconds(1))));
        assertThatThrownBy(() -> send(accessor))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("STOMP session token expired");
    }

    @Test
    void invalidCustomerClaimIsRejected() {
        when(decoder.decode("token"))
                .thenReturn(
                        Jwt.withTokenValue("token")
                                .header("alg", "none")
                                .subject("subject")
                                .claim("customer_id", "invalid")
                                .build());
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer token");
        assertThatThrownBy(() -> send(accessor)).isInstanceOf(BadCredentialsException.class);
        assertThat(accessor.getUser()).isNull();
    }
}

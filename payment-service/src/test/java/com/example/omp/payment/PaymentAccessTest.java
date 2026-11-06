package com.example.omp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.omp.payment.config.PaymentAccess;
import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.shared.domain.Money;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PaymentAccessTest {
    @Test
    void paymentLookupRequiresOwnerOrAdministrator() {
        var repository = mock(PaymentRepository.class);
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        when(repository.findById(id))
                .thenReturn(Optional.of(new Payment(UUID.randomUUID(), owner, Money.usd("1"))));
        var access = new PaymentAccess(repository);
        assertThat(access.canAccess(id, token(owner.toString(), false))).isTrue();
        assertThat(access.canAccess(id, token(UUID.randomUUID().toString(), false))).isFalse();
        assertThat(access.canAccess(id, null)).isFalse();
        assertThat(access.canAccess(id, token(UUID.randomUUID().toString(), true))).isTrue();
    }

    private JwtAuthenticationToken token(String customer, boolean admin) {
        Jwt jwt =
                Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject("user")
                        .claim("customer_id", customer)
                        .claim(
                                "realm_access",
                                Map.of("roles", admin ? List.of("admin") : List.of("customer")))
                        .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}

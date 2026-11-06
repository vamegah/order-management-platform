package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.domain.OrderNumber;
import com.example.omp.order.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class OrderAuthorizationTest {
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    @Mock OrderRepository orders;
    @InjectMocks OrderAuthorization authorization;

    @Test
    void creationOnlyAllowsTheVerifiedCustomerIdentity() {
        var token = token(CUSTOMER.toString());
        assertThat(authorization.ownsCustomer(CUSTOMER, token)).isTrue();
        assertThat(authorization.ownsCustomer(UUID.randomUUID(), token)).isFalse();
        verifyNoInteractions(orders);
    }

    @Test
    void idLookupUsesBothOrderAndCustomerIdentity() {
        UUID order = UUID.randomUUID();
        when(orders.existsByIdAndCustomerId(order, CUSTOMER)).thenReturn(true);
        assertThat(authorization.ownsOrder(order, token(CUSTOMER.toString()))).isTrue();
        verify(orders).existsByIdAndCustomerId(order, CUSTOMER);
    }

    @Test
    void foreignOrMissingOrderIsDenied() {
        UUID order = UUID.randomUUID();
        assertThat(authorization.ownsOrder(order, token(CUSTOMER.toString()))).isFalse();
        verify(orders).existsByIdAndCustomerId(order, CUSTOMER);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "invalid", "1-1-1-1-1", "00000000-0000-0000-0000-00000000010z"})
    void missingOrNoncanonicalClaimIsRejectedBeforeDatabaseAccess(String claim) {
        assertThat(authorization.ownsOrder(UUID.randomUUID(), token(claim))).isFalse();
        assertThat(authorization.ownsOrderNumber("ORD-2026-0001001", token(claim))).isFalse();
        verifyNoInteractions(orders);
    }

    @Test
    void claimMustBeAStringAndSubjectIsNotACustomerClaim() {
        for (Object claim : List.of(101, List.of(CUSTOMER.toString()))) {
            assertThat(authorization.ownsOrder(UUID.randomUUID(), token(claim))).isFalse();
        }
        var subjectOnly =
                Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject(CUSTOMER.toString())
                        .build();
        assertThat(authorization.ownsCustomer(CUSTOMER, subjectOnly)).isFalse();
        assertThat(authorization.ownsOrder(UUID.randomUUID(), null)).isFalse();
        verifyNoInteractions(orders);
    }

    @Test
    void orderNumberLookupRequiresMatchingOwnership() {
        var number = OrderNumber.of("ORD-2026-0001001");
        when(orders.existsByOrderNumberAndCustomerId(number, CUSTOMER)).thenReturn(true);
        assertThat(authorization.ownsOrderNumber(number.value(), token(CUSTOMER.toString())))
                .isTrue();
        verify(orders).existsByOrderNumberAndCustomerId(number, CUSTOMER);
    }

    @Test
    void malformedOrderNumberIsDeniedWithoutAQuery() {
        assertThat(authorization.ownsOrderNumber("invalid", token(CUSTOMER.toString()))).isFalse();
        verifyNoInteractions(orders);
    }

    private Jwt token(Object claim) {
        var builder = Jwt.withTokenValue("test").header("alg", "none").subject("subject");
        if (claim != null) builder.claim("customer_id", claim);
        return builder.build();
    }
}

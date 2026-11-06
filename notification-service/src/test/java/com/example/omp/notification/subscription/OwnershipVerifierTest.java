package com.example.omp.notification.subscription;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.clients.*;
import com.example.omp.notification.clients.dto.OrderDto;
import com.example.omp.notification.websocket.JwtPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnershipVerifierTest {
    private final CustomerClient customers = mock(CustomerClient.class);
    private final OrderClient orders = mock(OrderClient.class);
    private final PaymentClient payments = mock(PaymentClient.class);
    private final OwnershipVerifier verifier = new OwnershipVerifier(customers, orders, payments);
    private final UUID customer = UUID.randomUUID(), resource = UUID.randomUUID();
    private final JwtPrincipal principal =
            new JwtPrincipal("subject", "name", null, Set.of("customer"), customer);

    @Test
    void ownOrderComparesUuidAndUsesVerifiedClaimWithoutCustomerLookup() {
        when(orders.getOrder(resource))
                .thenReturn(new OrderDto(resource, "ORD", customer, "PLACED"));
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verifyNoInteractions(customers);
    }

    @Test
    void ownPaymentIsAllowedAndAnotherCustomerIsDenied() {
        when(payments.getPayment(resource))
                .thenReturn(new PaymentClient.PaymentOwner(resource, customer));
        verifier.verifyPaymentOwnership(resource.toString(), principal);
        when(payments.getPayment(resource))
                .thenReturn(new PaymentClient.PaymentOwner(resource, UUID.randomUUID()));
        assertThatThrownBy(() -> verifier.verifyPaymentOwnership(resource.toString(), principal))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        verifyNoInteractions(customers);
    }

    @Test
    void foreignOrderAndLookupFailureCannotGrantAccess() {
        when(orders.getOrder(resource))
                .thenReturn(new OrderDto(resource, "ORD", UUID.randomUUID(), "PLACED"));
        assertThatThrownBy(() -> verifier.verifyOrderOwnership(resource.toString(), principal))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        when(orders.getOrder(resource)).thenThrow(new IllegalStateException("Unavailable"));
        assertThatThrownBy(() -> verifier.verifyOrderOwnership(resource.toString(), principal))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminDoesNotNeedOwnershipLookups() {
        var admin = new JwtPrincipal("admin", "admin", null, Set.of("admin"));
        verifier.verifyOrderOwnership(resource.toString(), admin);
        verifier.verifyPaymentOwnership(resource.toString(), admin);
        verifyNoInteractions(customers, orders, payments);
    }

    @Test
    void legacyTokenUsesAuthenticatedCustomerLookupAndMissingIdentityIsDenied() {
        var legacy = new JwtPrincipal("subject", "name", null, Set.of("customer"));
        var dto = mock(com.example.omp.notification.clients.dto.CustomerDto.class);
        when(dto.id()).thenReturn(customer);
        when(customers.me()).thenReturn(dto);
        assertThat(verifier.resolveCustomerId(legacy)).isEqualTo(customer);
        when(customers.me()).thenReturn(null);
        assertThatThrownBy(() -> verifier.resolveCustomerId(legacy))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
    }
}

package com.example.omp.notification.subscription;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.clients.*;
import com.example.omp.notification.clients.dto.OrderDto;
import com.example.omp.notification.websocket.*;
import com.example.omp.shared.events.OrderEvents;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class OwnershipProofCacheTest {
    final CustomerClient customers = mock(CustomerClient.class);
    final OrderClient orders = mock(OrderClient.class);
    final PaymentClient payments = mock(PaymentClient.class);
    final OwnershipVerifier verifier = new OwnershipVerifier(customers, orders, payments);
    final UUID customer = UUID.randomUUID(), resource = UUID.randomUUID();
    final JwtPrincipal principal =
            new JwtPrincipal("subject", "name", null, Set.of("customer"), customer);

    @AfterEach
    void clear() {
        JwtThreadLocalHolder.clear();
    }

    OrderDto own(UUID id) {
        return new OrderDto(id, "ORD", customer, "PLACED");
    }

    @Test
    void exactTokenAndResourceShareAPositiveProof() {
        JwtThreadLocalHolder.set("token-a");
        when(orders.getOrder(resource)).thenReturn(own(resource));
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verify(orders).getOrder(resource);
    }

    @Test
    void changedTokenOrCustomerCannotReuseTheProof() {
        JwtThreadLocalHolder.set("token-a");
        when(orders.getOrder(resource)).thenReturn(own(resource));
        verifier.verifyOrderOwnership(resource.toString(), principal);
        var other = new JwtPrincipal("other", "other", null, Set.of("customer"), UUID.randomUUID());
        assertThatThrownBy(() -> verifier.verifyOrderOwnership(resource.toString(), other))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        JwtThreadLocalHolder.set("token-b");
        when(orders.getOrder(resource))
                .thenReturn(new OrderDto(resource, "ORD", UUID.randomUUID(), "PLACED"));
        assertThatThrownBy(() -> verifier.verifyOrderOwnership(resource.toString(), principal))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        verify(orders, times(3)).getOrder(resource);
    }

    @Test
    void resourceEventsInvalidateProofsAndFailuresAreNotCached() {
        JwtThreadLocalHolder.set("token");
        when(orders.getOrder(resource)).thenReturn(own(resource));
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verifier.invalidate(
                new OrderEvents.OrderPlaced(
                        UUID.randomUUID().toString(),
                        "ORDER_PLACED",
                        Instant.now(),
                        1,
                        resource.toString(),
                        "ORD",
                        customer.toString(),
                        "PLACED",
                        "actor"));
        when(orders.getOrder(resource)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> verifier.verifyOrderOwnership(resource.toString(), principal))
                .isInstanceOf(IllegalStateException.class);
        doReturn(own(resource)).when(orders).getOrder(resource);
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verify(orders, times(3)).getOrder(resource);
    }

    @Test
    void wrongResourceResponseCannotAuthorizeTheRequestedResource() {
        JwtThreadLocalHolder.set("token");
        when(orders.getOrder(resource)).thenReturn(own(UUID.randomUUID()));
        assertThatThrownBy(() -> verifier.verifyOrderOwnership(resource.toString(), principal))
                .isInstanceOf(SubscriptionUnauthorizedException.class);
        doReturn(own(resource)).when(orders).getOrder(resource);
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verify(orders, times(2)).getOrder(resource);
    }

    @Test
    void concurrentConnectionsCoalesceOneOwnershipRequest() throws Exception {
        var ready = new CountDownLatch(16);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(orders.getOrder(resource))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return own(resource);
                        });
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    JwtThreadLocalHolder.set("shared-token");
                                    ready.countDown();
                                    try {
                                        verifier.verifyOrderOwnership(
                                                resource.toString(), principal);
                                    } finally {
                                        JwtThreadLocalHolder.clear();
                                    }
                                }));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        verify(orders).getOrder(resource);
    }

    @Test
    void boundedProofStorageEvictsOldResources() {
        JwtThreadLocalHolder.set("token");
        when(orders.getOrder(any())).thenAnswer(call -> own(call.getArgument(0)));
        verifier.verifyOrderOwnership(resource.toString(), principal);
        for (int i = 0; i < 4096; i++)
            verifier.verifyOrderOwnership(UUID.randomUUID().toString(), principal);
        verifier.verifyOrderOwnership(resource.toString(), principal);
        verify(orders, times(2)).getOrder(resource);
    }
}

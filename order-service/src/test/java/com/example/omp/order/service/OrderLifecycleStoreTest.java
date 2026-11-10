package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderNumber;
import com.example.omp.order.domain.OrderStatus;
import com.example.omp.order.domain.ShippingAddress;
import com.example.omp.order.error.OrderNotFoundException;
import com.example.omp.order.events.OrderEventPublisher;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Policy boundaries independent of SQL concurrency/rollback, which CoreOrderLifecycleIT exercises.
 */
class OrderLifecycleStoreTest {
    private JdbcTemplate jdbc;
    private OrderTransactionService orders;
    private OrderCleanupQueue cleanup;
    private OrderEventPublisher events;
    private OrderLifecycleStore store;
    private Order order;
    private OrderLifecycleStore.Claim claim;
    private boolean live;

    @BeforeEach
    void setup() {
        live = true;
        order =
                new Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "owner@example.test",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        claim = new OrderLifecycleStore.Claim(order.getId(), UUID.randomUUID());
        jdbc =
                mock(
                        JdbcTemplate.class,
                        call -> {
                            return switch (call.getMethod().getName()) {
                                case "queryForList" -> live ? List.of(order.getId()) : List.of();
                                case "queryForMap" ->
                                        Map.of(
                                                "requested_by",
                                                "operator",
                                                "reason",
                                                "Cancel safely");
                                case "queryForObject" -> {
                                    Class<?> type = call.getArgument(1);
                                    if (type == Boolean.class) yield live;
                                    if (type == Long.class) yield 0L;
                                    if (type == Double.class) yield 0.0;
                                    yield null;
                                }
                                case "update" -> 1;
                                case "query" -> List.of(claim);
                                default -> RETURNS_DEFAULTS.answer(call);
                            };
                        });
        orders = mock(OrderTransactionService.class);
        when(orders.findAndApply(eq(order.getId()), any()))
                .thenAnswer(
                        call -> {
                            Consumer<Order> mutation = call.getArgument(1);
                            mutation.accept(order);
                            return order;
                        });
        cleanup = mock(OrderCleanupQueue.class);
        events = mock(OrderEventPublisher.class);
        store = new OrderLifecycleStore(jdbc, orders, cleanup, events);
    }

    @Test
    void batchesAreBoundedAndExplicitClaimsRetainTheirOrder() {
        assertThat(store.claim(10)).containsExactly(claim);
        assertThat(store.claimOrder(order.getId())).containsExactly(claim);
        assertThatThrownBy(() -> store.claim(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellingAnUnfulfilledOrderRecordsOneLocalDecisionAndDurableIntent() {
        store.requestCancellation(claim, "Hold expired");
        store.requestCancellation(claim, "Hold expired");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory()).hasSize(2);
        verify(events).publishCancelled(order, "system:lifecycle", "Hold expired");
        verify(cleanup).trackCancellation(order.getId(), "system:lifecycle", "Hold expired");
    }

    @Test
    void partialFulfillmentNeedsAnExplicitCompensationAdmissionBeforeCancellation() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                order, "status", OrderStatus.FULFILLING);
        store.requestCancellation(claim, "Expired required hold");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLING);
        verifyNoInteractions(events);
        UUID reservation = UUID.randomUUID(), payment = UUID.randomUUID();
        store.admitCompensation(claim, List.of(reservation), List.of(payment));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(events).publishCancelled(order, "operator", "Cancel safely");
        verify(cleanup).enqueue(order.getId(), "RESERVATION", reservation);
        verify(cleanup).enqueue(order.getId(), "PAYMENT", payment);
    }

    @Test
    void staleLeasesCannotRequestCancellationOrAdmitResources() {
        live = false;
        assertThatThrownBy(() -> store.requestCancellation(claim, "Expired hold"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.admitCompensation(claim, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.requireCurrent(claim))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        verifyNoInteractions(orders, cleanup, events);
    }

    @Test
    void shipmentAndMalformedOrOversizedPlansCannotBeAdmitted() {
        assertThatThrownBy(() -> store.admitCompensation(claim, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
        for (List<UUID> bad :
                List.of(
                        Arrays.asList((UUID) null),
                        Collections.nCopies(2, UUID.randomUUID()),
                        java.util.stream.IntStream.range(0, 101)
                                .mapToObj(i -> UUID.randomUUID())
                                .toList()))
            assertThatThrownBy(() -> store.admitCompensation(claim, bad, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.admitCompensation(claim, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cleanup, events);
    }

    @Test
    void completionAndFailureRemainVisibleWithoutResettingAClaim() {
        assertThat(store.finish(claim, false)).isTrue();
        assertThat(store.finish(claim, true)).isTrue();
        store.fail(claim, new IllegalStateException("private downstream details"));
        assertThat(store.attentionCount()).isZero();
        assertThat(store.lagSeconds()).isZero();
        assertThat(store.legacyPending(order.getId())).isTrue();
        live = false;
        assertThat(store.legacyPending(order.getId())).isFalse();
    }

    @Test
    void operatorRetriesRequireABoundedVerifiedActorAndReason() {
        store.retry(order.getId(), "subject", " Resolve original ledger ");
        for (String invalid : Arrays.asList(null, "", " ", "a".repeat(101)))
            assertThatThrownBy(() -> store.retry(order.getId(), invalid, "reason"))
                    .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        for (String invalid : Arrays.asList(null, "", " ", "a".repeat(501)))
            assertThatThrownBy(() -> store.retry(order.getId(), "subject", invalid))
                    .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        when(jdbc.update(anyString(), eq(order.getId()))).thenReturn(0);
        assertThatThrownBy(() -> store.retry(order.getId(), "subject", "reason"))
                .isInstanceOf(OrderNotFoundException.class);
        live = false;
        assertThatThrownBy(() -> store.inspect(order.getId()))
                .isInstanceOf(OrderNotFoundException.class);
    }
}

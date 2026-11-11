package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.*;
import com.example.omp.order.domain.Order;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.shared.domain.Money;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class OrderLegacyLifecycleStoreTest {
    private Order order;
    private OrderLine line;
    private JdbcTemplate jdbc;
    private OrderTransactionService orders;
    private OrderLifecycleStore leases;
    private OrderCleanupQueue cleanup;
    private OrderEventPublisher events;
    private OrderLegacyLifecycleStore store;
    private OrderLifecycleStore.Claim claim;
    private boolean pending;
    private long captured;

    @BeforeEach
    void setup() {
        order =
                new Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "owner@example.test",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        line = new OrderLine(UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1);
        ReflectionTestUtils.setField(line, "id", UUID.randomUUID());
        order.addLine(line);
        pending = true;
        captured = 1;
        jdbc =
                mock(
                        JdbcTemplate.class,
                        call ->
                                switch (call.getMethod().getName()) {
                                    case "queryForList" ->
                                            pending ? List.of(order.getId()) : List.of();
                                    case "queryForObject" -> captured;
                                    case "update" -> 1;
                                    default -> RETURNS_DEFAULTS.answer(call);
                                });
        orders = mock(OrderTransactionService.class);
        when(orders.findByIdFull(order.getId())).thenReturn(order);
        when(orders.findAndApply(eq(order.getId()), any()))
                .thenAnswer(
                        call -> {
                            Consumer<Order> mutation = call.getArgument(1);
                            mutation.accept(order);
                            return order;
                        });
        leases = mock(OrderLifecycleStore.class);
        cleanup = mock(OrderCleanupQueue.class);
        events = mock(OrderEventPublisher.class);
        store = new OrderLegacyLifecycleStore(jdbc, leases, orders, cleanup, events);
        ReflectionTestUtils.setField(store, "entities", mock(EntityManager.class));
        claim = new OrderLifecycleStore.Claim(order.getId(), UUID.randomUUID());
    }

    @Test
    void verifiedOriginalReferencesPreserveTheBusinessStatusAndUnknownWarehouse() {
        UUID reservation = UUID.randomUUID();
        line.setReservationId(reservation);
        var proof =
                new ReservationDto(
                        reservation,
                        "SKU",
                        "OBSERVED",
                        1,
                        order.getId(),
                        "RESERVATION_STATUS_ACTIVE",
                        0,
                        0);
        store.resolve(claim, order, Map.of(line.getId(), proof), false);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(line.getReservationWarehouseId()).isNull();
        verifyNoInteractions(events, cleanup);
    }

    @Test
    void missingHistoricalInventoryUsesTheApprovedAuditedCancellationPolicy() {
        store.resolve(claim, order, Map.of(), true);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(line.getReservationId()).isNull();
        assertThat(line.getReservationWarehouseId()).isNull();
        verify(events).publishCancelled(eq(order), eq("system:legacy-lifecycle"), anyString());
        verify(cleanup)
                .trackCancellation(eq(order.getId()), eq("system:legacy-lifecycle"), anyString());
    }

    @Test
    void aPartialShipmentRetainsPendingCompensationUntilTheFinalAdmission() {
        ReflectionTestUtils.setField(order, "status", OrderStatus.FULFILLING);
        store.resolve(claim, order, Map.of(), true);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLING);
        verifyNoInteractions(events);
        assertThatThrownBy(() -> store.finishCancellation(claim))
                .isInstanceOf(IllegalStateException.class);
        order.cancel("operator", "Inventory compensated");
        store.finishCancellation(claim);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void terminalExceptionsDoNotRewriteTheHistoricalBusinessStatus() {
        for (OrderStatus terminal : List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED)) {
            ReflectionTestUtils.setField(order, "status", terminal);
            store.resolve(claim, order, Map.of(), true);
            assertThat(order.getStatus()).isEqualTo(terminal);
            assertThat(order.getStatusHistory()).hasSize(1);
        }
        verifyNoInteractions(events, cleanup);
    }

    @Test
    void changedIdentitiesMissingProofAndPhysicalShipmentsFailClosed() {
        captured = 0;
        assertThatThrownBy(() -> store.resolve(claim, order, Map.of(), true))
                .isInstanceOf(IllegalStateException.class);
        captured = 1;
        assertThatThrownBy(() -> store.resolve(claim, order, Map.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.setField(order, "status", OrderStatus.SHIPPED);
        assertThatThrownBy(() -> store.resolve(claim, order, Map.of(), true))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events, cleanup);
    }

    @Test
    void completedResolutionAndStaleVersionsCannotChangeAnOrderAgain() {
        pending = false;
        store.resolve(claim, order, Map.of(), true);
        store.finishCancellation(claim);
        verifyNoInteractions(orders, cleanup, events);
        pending = true;
        var changed = mock(Order.class);
        when(changed.getVersion()).thenReturn(order.getVersion() + 1);
        when(orders.findByIdFull(order.getId())).thenReturn(changed);
        assertThatThrownBy(() -> store.resolve(claim, order, Map.of(), true))
                .isInstanceOf(IllegalStateException.class);
    }
}

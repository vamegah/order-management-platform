package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.*;
import com.example.omp.order.clients.grpc.dto.*;
import com.example.omp.order.domain.*;
import com.example.omp.order.domain.Order;
import com.example.omp.proto.payment.FulfillmentAction;
import com.example.omp.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderLifecycleCoordinatorTest {
    private OrderLifecycleStore store;
    private OrderTransactionService orders;
    private InventoryClient inventory;
    private PaymentClient payments;
    private OrderCleanupQueue cleanup;
    private OrderFulfillmentStore fulfillment;
    private OrderLegacyLifecycleResolver legacy;
    private OrderLifecycleCoordinator coordinator;
    private SimpleMeterRegistry metrics;
    private Order order;
    private OrderLifecycleStore.Claim claim;
    private UUID reservationId;

    @BeforeEach
    void setup() {
        store = mock(OrderLifecycleStore.class);
        orders = mock(OrderTransactionService.class);
        inventory = mock(InventoryClient.class);
        payments = mock(PaymentClient.class);
        cleanup = mock(OrderCleanupQueue.class);
        fulfillment = mock(OrderFulfillmentStore.class);
        legacy = mock(OrderLegacyLifecycleResolver.class);
        metrics = new SimpleMeterRegistry();
        coordinator =
                new OrderLifecycleCoordinator(
                        store, orders, inventory, payments, cleanup, fulfillment, legacy, metrics);
        order =
                new Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "owner@example.test",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        reservationId = UUID.randomUUID();
        var line = new OrderLine(UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1);
        line.bindReservation(reservationId, "WH-1");
        order.addLine(line);
        status(OrderStatus.CANCELLED);
        claim = new OrderLifecycleStore.Claim(order.getId(), UUID.randomUUID());
        when(store.claim(10)).thenReturn(List.of(claim));
        when(orders.findByIdFull(order.getId())).thenReturn(order);
        when(inventory.findByOrder(order.getId()))
                .thenReturn(List.of(hold("RESERVATION_STATUS_ACTIVE")));
        when(inventory.compensateOrder(order.getId()))
                .thenReturn(List.of(hold("RESERVATION_STATUS_RELEASED")));
        when(payments.findByOrder(order.getId())).thenReturn(List.of(payment("OPEN")));
        when(payments.decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL))
                .thenReturn(payment("CANCELLING"));
        when(payments.decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND))
                .thenReturn(payment("REFUNDABLE"));
    }

    private void status(OrderStatus status) {
        ReflectionTestUtils.setField(order, "status", status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sku", "warehouse", "quantity", "status"})
    void malformedDiscoveryMetadataCannotAuthorizeCancellationOrRefunds(String fault) {
        var malformed =
                new ReservationDto(
                        reservationId,
                        fault.equals("sku") ? null : "SKU",
                        fault.equals("warehouse") ? " WH-1" : "WH-1",
                        fault.equals("quantity") ? 0 : 1,
                        order.getId(),
                        fault.equals("status") ? "UNRECOGNIZED" : "RESERVATION_STATUS_ACTIVE",
                        0,
                        0);
        when(inventory.findByOrder(order.getId())).thenReturn(List.of(malformed));
        coordinator.retryDue();
        verify(store).fail(eq(claim), any(IllegalStateException.class));
        verify(inventory, never()).compensateOrder(any());
        verify(payments, never()).decide(any(), any(), any());
        verify(store, never()).admitCompensation(any(), anyList(), anyList());
    }

    @Test
    void aCompensationReplyCannotChangeTheDiscoveredSkuWarehouseOrQuantity() {
        when(inventory.compensateOrder(order.getId()))
                .thenReturn(
                        List.of(
                                new ReservationDto(
                                        reservationId,
                                        "OTHER",
                                        "WH-1",
                                        1,
                                        order.getId(),
                                        "RESERVATION_STATUS_RELEASED",
                                        0,
                                        0)));
        coordinator.retryDue();
        verify(store).fail(eq(claim), any(IllegalStateException.class));
        verify(payments, never())
                .decide(any(), any(), eq(FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND));
        verify(store, never()).admitCompensation(any(), anyList(), anyList());
    }

    private ReservationDto hold(String status) {
        return new ReservationDto(reservationId, "SKU", "WH-1", 1, order.getId(), status, 0, 0);
    }

    private PaymentIntentDto payment(String state) {
        return new PaymentIntentDto(
                order.getPaymentId(),
                order.getId(),
                order.getCustomerId(),
                "10.00",
                "USD",
                "PAYMENT_STATUS_CONFIRMED",
                "provider-original",
                0,
                0,
                state);
    }

    @Test
    void cancellationDiscoversOwnedResourcesBeforeCompensationAndAdmitsOneCompletePlan() {
        ReflectionTestUtils.setField(order.getLines().getFirst(), "reservationId", null);
        coordinator.retryDue();
        var sequence = inOrder(payments, inventory, store, cleanup);
        sequence.verify(payments)
                .decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL);
        sequence.verify(inventory).compensateOrder(order.getId());
        sequence.verify(payments)
                .decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND);
        sequence.verify(store)
                .admitCompensation(claim, List.of(reservationId), List.of(order.getPaymentId()));
        sequence.verify(cleanup).retryOrder(order.getId(), 100);
        sequence.verify(store).finish(claim, true);
        assertThat(metrics.counter("order.lifecycle.scans").count()).isEqualTo(1);
    }

    @Test
    void healthyAcceptedOrdersKeepTheirHoldsAndPayment() {
        status(OrderStatus.CONFIRMED);
        when(inventory.get(reservationId)).thenReturn(hold("RESERVATION_STATUS_ACTIVE"));
        when(payments.get(order.getPaymentId())).thenReturn(payment("OPEN"));
        coordinator.retryDue();
        verify(store).finish(claim, false);
        verify(inventory, never()).compensateOrder(any());
        verify(payments, never()).decide(any(), any(), any());
        verifyNoInteractions(cleanup);
    }

    @Test
    void anExpiredRequiredHoldCancelsTheAcceptedOrderAndCleansItsCharge() {
        status(OrderStatus.CONFIRMED);
        when(inventory.get(reservationId)).thenReturn(hold("RESERVATION_STATUS_EXPIRED"));
        when(payments.get(order.getPaymentId())).thenReturn(payment("OPEN"));
        doAnswer(
                        call -> {
                            status(OrderStatus.CANCELLED);
                            return null;
                        })
                .when(store)
                .requestCancellation(eq(claim), anyString());
        when(inventory.compensateOrder(order.getId()))
                .thenReturn(List.of(hold("RESERVATION_STATUS_EXPIRED")));
        coordinator.retryDue();
        verify(store).requestCancellation(claim, "Required inventory expired or was released");
        verify(store)
                .admitCompensation(claim, List.of(reservationId), List.of(order.getPaymentId()));
    }

    @Test
    void compensationAcknowledgesRestockedCommittedInventoryWithoutReleasingItAgain() {
        when(inventory.findByOrder(order.getId()))
                .thenReturn(List.of(hold("RESERVATION_STATUS_COMMITTED")));
        when(inventory.compensateOrder(order.getId()))
                .thenReturn(List.of(hold("RESERVATION_STATUS_RESTOCKED")));
        coordinator.retryDue();
        verify(store)
                .admitCompensation(claim, List.of(reservationId), List.of(order.getPaymentId()));
        verify(inventory, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "DUPLICATE", "FOREIGN", "OVERSIZED"})
    void malformedReservationDiscoveryCannotMutateEitherAuthority(String variant) {
        List<ReservationDto> resources =
                switch (variant) {
                    case "NULL" -> null;
                    case "DUPLICATE" ->
                            List.of(
                                    hold("RESERVATION_STATUS_ACTIVE"),
                                    hold("RESERVATION_STATUS_ACTIVE"));
                    case "FOREIGN" ->
                            List.of(
                                    new ReservationDto(
                                            reservationId,
                                            "SKU",
                                            "WH-1",
                                            1,
                                            UUID.randomUUID(),
                                            "RESERVATION_STATUS_ACTIVE",
                                            0,
                                            0));
                    default ->
                            IntStream.range(0, 101)
                                    .mapToObj(
                                            i ->
                                                    new ReservationDto(
                                                            UUID.randomUUID(),
                                                            "SKU-" + i,
                                                            "WH-1",
                                                            1,
                                                            order.getId(),
                                                            "RESERVATION_STATUS_ACTIVE",
                                                            0,
                                                            0))
                                    .toList();
                };
        when(inventory.findByOrder(order.getId())).thenReturn(resources);
        coordinator.retryDue();
        verify(store).fail(eq(claim), any(IllegalStateException.class));
        verify(payments, never()).decide(any(), any(), any());
        verify(inventory, never()).compensateOrder(any());
        verifyNoInteractions(cleanup);
    }

    @Test
    void failedPaymentDiscoveryCannotLeaveAPartialReservationAdmission() {
        when(payments.findByOrder(order.getId()))
                .thenThrow(new IllegalStateException("Unavailable"));
        coordinator.retryDue();
        verify(store).fail(eq(claim), any());
        verify(store, never()).admitCompensation(any(), any(), any());
        verify(inventory, never()).compensateOrder(any());
        verifyNoInteractions(cleanup);
    }

    @Test
    void aMissingCanonicalPaymentKeepsTheOrderInAttentionInsteadOfAssumingNoCharge() {
        when(payments.findByOrder(order.getId())).thenReturn(List.of());
        coordinator.retryDue();
        verify(store).fail(eq(claim), any());
        verify(inventory, never()).compensateOrder(any());
    }

    @Test
    void aShipmentThatWonThePaymentDecisionFinishesWithoutRefundOrRestock() {
        status(OrderStatus.CANCELLING);
        when(payments.decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL))
                .thenReturn(payment("SHIPPED"));
        when(fulfillment.finish(order.getId(), payment("SHIPPED"))).thenReturn(true);
        coordinator.retryDue();
        verify(fulfillment).finish(order.getId(), payment("SHIPPED"));
        verify(store).finish(claim, false);
        verify(inventory, never()).compensateOrder(any());
        verifyNoInteractions(cleanup);
    }

    @Test
    void aForeignShipmentDecisionCannotResurrectAnAlreadyCancelledOrder() {
        when(payments.decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL))
                .thenReturn(payment("SHIPPED"));
        coordinator.retryDue();
        verify(store).fail(eq(claim), any());
        verifyNoInteractions(fulfillment, cleanup);
        verify(inventory, never()).compensateOrder(any());
    }

    @Test
    void incompleteInventoryCompensationCannotUnlockRefunds() {
        when(inventory.compensateOrder(order.getId()))
                .thenReturn(List.of(hold("RESERVATION_STATUS_ACTIVE")));
        coordinator.retryDue();
        verify(payments, never())
                .decide(any(), any(), eq(FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND));
        verify(store, never()).admitCompensation(any(), any(), any());
        verify(store).fail(eq(claim), any());
    }

    @Test
    void anExpiredLeaseCannotAcknowledgeRemoteCompensationOrRunLocalCleanup() {
        doThrow(new IllegalStateException("Lease lost"))
                .when(store)
                .admitCompensation(any(), any(), any());
        coordinator.retryDue();
        verify(store).fail(eq(claim), any());
        verify(store, never()).finish(any(), anyBoolean());
        verifyNoInteractions(cleanup);
    }

    @Test
    void legacyResolutionIsBoundToTheClaimAndPreservesTerminalOrders() {
        status(OrderStatus.DELIVERED);
        when(store.legacyPending(order.getId())).thenReturn(true);
        coordinator.retryDue();
        verify(legacy).resolve(claim, order, List.of(hold("RESERVATION_STATUS_ACTIVE")));
        verify(store).finish(claim, false);
        verify(inventory, never()).compensateOrder(any());
    }

    @Test
    void ambientTransactionsAndInterruptionPreventNetworkWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(coordinator::retryDue).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        Thread.currentThread().interrupt();
        try {
            coordinator.retryDue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(inventory, payments, cleanup);
    }
}

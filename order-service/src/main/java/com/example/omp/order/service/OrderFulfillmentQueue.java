package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable shipment execution. Never holds a database transaction across an inventory RPC. */
@Service
public class OrderFulfillmentQueue {
    private final OrderFulfillmentStore store;
    private final InventoryClient inventory;
    private final MeterRegistry metrics;
    private final PaymentClient payments;
    private final OrderTransactionService orders;

    @org.springframework.beans.factory.annotation.Value("${omp.order.recovery.token-file:}")
    private String tokenFile = "";

    public OrderFulfillmentQueue(
            OrderFulfillmentStore store,
            InventoryClient inventory,
            PaymentClient payments,
            OrderTransactionService orders,
            MeterRegistry metrics) {
        this.store = store;
        this.inventory = inventory;
        this.metrics = metrics;
        this.payments = payments;
        this.orders = orders;
        metrics.gauge("order.fulfillment.pending", store, OrderFulfillmentStore::pendingCount);
    }

    public int retryOrder(UUID orderId, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Fulfillment limit must be 1..100");
        return ServiceTokenScope.withProjectedIdentity(
                tokenFile, () -> retryOrderWithIdentity(orderId, limit));
    }

    private int retryOrderWithIdentity(UUID orderId, int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Fulfillment RPCs require a committed shipment intent");
        if (Thread.currentThread().isInterrupted()) return 0;
        if (!store.startAttempt(orderId)) return 0;
        try {
            var order = orders.findByIdFull(orderId);
            if (order.getStatus() != com.example.omp.order.domain.OrderStatus.FULFILLING) return 0;
            OrderPaymentPolicy.requireMatchingPayment(order, payments.get(order.getPaymentId()));
            var decision =
                    payments.decide(
                            order.getPaymentId(),
                            orderId,
                            com.example.omp.proto.payment.FulfillmentAction
                                    .FULFILLMENT_ACTION_BEGIN);
            OrderPaymentPolicy.requireMatchingPayment(order, decision);
            if (!java.util.Set.of("FULFILLING", "SHIPPED").contains(decision.fulfillmentState()))
                throw new IllegalStateException("Payment did not authorize fulfillment");
        } catch (RuntimeException failure) {
            store.recordFailure(orderId, failure);
            metrics.counter("order.fulfillment.failures").increment();
            LoggerFactory.getLogger(getClass())
                    .warn("Shipment payment verification pending: order={}", orderId);
            return 0;
        }
        int completed = 0;
        for (int index = 0; index < limit && !Thread.currentThread().isInterrupted(); index++) {
            var claimed = store.claimTask(orderId);
            if (claimed.isEmpty()) break;
            var task = claimed.get();
            boolean abort = false;
            try {
                var reservation = inventory.get(task.reservationId());
                validate(task, reservation);
                if ("RESERVATION_STATUS_ACTIVE".equals(reservation.status())) {
                    reservation = inventory.commit(task.reservationId().toString());
                    validate(task, reservation);
                }
                if (!"RESERVATION_STATUS_COMMITTED".equals(reservation.status())) {
                    if (java.util.Set.of(
                                    "RESERVATION_STATUS_EXPIRED",
                                    "RESERVATION_STATUS_RELEASED",
                                    "RESERVATION_STATUS_RESTOCKED")
                            .contains(reservation.status())) {
                        abort = true;
                        store.requestAbort(orderId, "Required shipment inventory is unavailable");
                    }
                    throw new IllegalStateException("Shipment reservation is not committed");
                }
                if (store.completeTask(task)) {
                    completed++;
                    metrics.counter("order.fulfillment.reservations.completed").increment();
                }
            } catch (RuntimeException failure) {
                store.failTask(task, failure);
                metrics.counter("order.fulfillment.failures").increment();
                LoggerFactory.getLogger(getClass())
                        .warn(
                                "Shipment reservation remains pending: order={} task={}",
                                orderId,
                                task.id());
                if (abort) return completed;
            }
        }
        try {
            if (!Thread.currentThread().isInterrupted() && store.readyForCompletion(orderId)) {
                var order = orders.findByIdFull(orderId);
                var payment =
                        payments.decide(
                                order.getPaymentId(),
                                orderId,
                                com.example.omp.proto.payment.FulfillmentAction
                                        .FULFILLMENT_ACTION_SHIP);
                OrderPaymentPolicy.requireMatchingPayment(order, payment);
                if (store.finish(orderId, payment))
                    metrics.counter("order.fulfillment.completed").increment();
            }
        } catch (RuntimeException failure) {
            store.recordFailure(orderId, failure);
            metrics.counter("order.fulfillment.failures").increment();
            LoggerFactory.getLogger(getClass())
                    .warn("Shipment completion remains pending: order={}", orderId);
        }
        return completed;
    }

    public void retryDue() {
        for (UUID id : store.dueOrders()) {
            try {
                retryOrder(id, 10);
            } catch (RuntimeException failure) {
                metrics.counter("order.fulfillment.failures").increment();
                LoggerFactory.getLogger(getClass()).warn("Shipment will retry: order={}", id);
            }
        }
    }

    private void validate(OrderFulfillmentStore.Task task, ReservationDto reservation) {
        if (reservation == null
                || !task.reservationId().equals(reservation.id())
                || !task.orderId().equals(reservation.orderId())
                || !task.sku().equals(reservation.sku())
                || (task.warehouseId() != null
                        && !task.warehouseId().equals(reservation.warehouseId()))
                || task.quantity() != reservation.quantity())
            throw new IllegalStateException("Shipment reservation does not match the order line");
    }
}

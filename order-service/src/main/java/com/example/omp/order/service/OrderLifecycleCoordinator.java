package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderStatus;
import com.example.omp.proto.payment.FulfillmentAction;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates durable authority decisions; all cross-service calls are outside transactions. */
@Service
public class OrderLifecycleCoordinator {
    private final OrderLifecycleStore store;
    private final OrderTransactionService orders;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final OrderCleanupQueue cleanup;
    private final OrderFulfillmentStore fulfillment;
    private final OrderLegacyLifecycleResolver legacy;
    private final MeterRegistry metrics;

    @org.springframework.beans.factory.annotation.Value("${omp.order.recovery.token-file:}")
    private String tokenFile = "";

    public OrderLifecycleCoordinator(
            OrderLifecycleStore store,
            OrderTransactionService orders,
            InventoryClient inventory,
            PaymentClient payments,
            OrderCleanupQueue cleanup,
            OrderFulfillmentStore fulfillment,
            OrderLegacyLifecycleResolver legacy,
            MeterRegistry metrics) {
        this.store = store;
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
        this.cleanup = cleanup;
        this.fulfillment = fulfillment;
        this.legacy = legacy;
        this.metrics = metrics;
        metrics.gauge("order.lifecycle.attention", store, OrderLifecycleStore::attentionCount);
        metrics.gauge("order.lifecycle.lag.seconds", store, OrderLifecycleStore::lagSeconds);
    }

    public void retryDue() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Lifecycle RPCs require no ambient transaction");
        for (var claim : store.claim(10)) {
            if (Thread.currentThread().isInterrupted()) return;
            runClaim(claim);
        }
    }

    /**
     * Best-effort fast path uses only the configured workload identity, never elevated user roles.
     */
    public void retryOrder(UUID orderId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Lifecycle RPCs require no ambient transaction");
        if (tokenFile.isBlank()) return;
        try (var identity =
                com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope.open(
                        com.example.omp.order.clients.grpc.interceptor.ServiceTokenFile.read(
                                java.nio.file.Path.of(tokenFile)))) {
            for (var claim : store.claimOrder(orderId)) runClaim(claim);
        } catch (java.io.IOException failure) {
            metrics.counter("order.lifecycle.worker.failures").increment();
        }
    }

    private void runClaim(OrderLifecycleStore.Claim claim) {
        try {
            reconcile(claim);
            metrics.counter("order.lifecycle.scans").increment();
        } catch (RuntimeException failure) {
            store.fail(claim, failure);
            metrics.counter("order.lifecycle.failures").increment();
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "Order lifecycle requires retry: order={} failure={}",
                            claim.orderId(),
                            failure.getClass().getSimpleName());
        }
    }

    private void reconcile(OrderLifecycleStore.Claim claim) {
        Order order = orders.findByIdFull(claim.orderId());
        if (store.legacyPending(order.getId())) {
            var discovered = inventory.findByOrder(order.getId());
            validateResources(
                    order.getId(), discovered, ReservationDto::id, ReservationDto::orderId);
            legacy.resolve(claim, order, discovered);
            order = orders.findByIdFull(order.getId());
        }
        if (order.getStatus() == OrderStatus.SHIPPED
                || order.getStatus() == OrderStatus.DELIVERED) {
            store.finish(claim, false);
            return;
        }
        if (order.getStatus() != OrderStatus.CANCELLED
                && order.getStatus() != OrderStatus.CANCELLING) {
            boolean missingInventory = false;
            var identities = new HashSet<UUID>();
            if (order.getLines().isEmpty() || order.getLines().size() > 100)
                throw new IllegalStateException("Order reference repair is required");
            for (var line : order.getLines()) {
                if (line.getReservationId() == null || !identities.add(line.getReservationId()))
                    throw new IllegalStateException("Order reference repair is required");
                var reservation = inventory.get(line.getReservationId());
                if (reservation == null
                        || !line.getReservationId().equals(reservation.id())
                        || !order.getId().equals(reservation.orderId())
                        || !line.getProductSku().equals(reservation.sku())
                        || line.getQuantity() != reservation.quantity()
                        || (line.getReservationWarehouseId() != null
                                && !line.getReservationWarehouseId()
                                        .equals(reservation.warehouseId())))
                    throw new IllegalStateException("Order reference ownership requires repair");
                if (!Set.of("RESERVATION_STATUS_ACTIVE", "RESERVATION_STATUS_COMMITTED")
                        .contains(reservation.status())) missingInventory = true;
            }
            var payment = order.getPaymentId() == null ? null : payments.get(order.getPaymentId());
            if (payment == null
                    || !order.getId().equals(payment.orderId())
                    || !order.getCustomerId().equals(payment.customerId()))
                throw new IllegalStateException("Order payment requires reconciliation");
            boolean unavailablePayment =
                    !Set.of("PAYMENT_STATUS_PENDING", "PAYMENT_STATUS_CONFIRMED")
                                    .contains(payment.status())
                            || Set.of("CANCELLING", "REFUNDABLE")
                                    .contains(payment.fulfillmentState());
            if (missingInventory || unavailablePayment) {
                store.requestCancellation(
                        claim,
                        missingInventory
                                ? "Required inventory expired or was released"
                                : "Order payment is no longer usable");
                order = orders.findByIdFull(order.getId());
            } else {
                store.finish(claim, false);
                return;
            }
        }
        compensate(claim, order);
    }

    private void compensate(OrderLifecycleStore.Claim claim, Order order) {
        // Validate both complete result sets before any new decision or admission.
        var reservations = inventory.findByOrder(order.getId());
        var foundPayments = payments.findByOrder(order.getId());
        validateReservations(order.getId(), reservations);
        validateResources(
                order.getId(), foundPayments, PaymentIntentDto::id, PaymentIntentDto::orderId);
        if (order.getPaymentId() != null
                && foundPayments.stream().noneMatch(p -> order.getPaymentId().equals(p.id())))
            throw new IllegalStateException("Original order payment was not discovered");
        for (var payment : foundPayments) {
            var decision =
                    payments.decide(
                            payment.id(),
                            order.getId(),
                            FulfillmentAction.FULFILLMENT_ACTION_CANCEL);
            if ("SHIPPED".equals(decision.fulfillmentState())) {
                if (order.getStatus() != OrderStatus.CANCELLING)
                    throw new IllegalStateException(
                            "Shipment decision requires operator reconciliation");
                OrderPaymentPolicy.requireMatchingPayment(order, decision);
                if (!fulfillment.finish(order.getId(), decision))
                    throw new IllegalStateException("Shipment decision has incomplete local work");
                store.finish(claim, false);
                return;
            }
            if (!Set.of("CANCELLING", "REFUNDABLE").contains(decision.fulfillmentState()))
                throw new IllegalStateException("Payment did not authorize compensation");
        }
        var compensated = inventory.compensateOrder(order.getId());
        validateReservations(order.getId(), compensated);
        var original =
                reservations.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ReservationDto::id, Function.identity()));
        for (var acknowledgment : compensated) {
            var before = original.get(acknowledgment.id());
            if (before != null
                    && (!before.sku().equals(acknowledgment.sku())
                            || !before.warehouseId().equals(acknowledgment.warehouseId())
                            || before.quantity() != acknowledgment.quantity()))
                throw new IllegalStateException("Inventory compensation identity changed");
        }
        if (!new HashSet<>(compensated.stream().map(ReservationDto::id).toList())
                        .containsAll(reservations.stream().map(ReservationDto::id).toList())
                || compensated.stream()
                        .anyMatch(
                                r ->
                                        !Set.of(
                                                        "RESERVATION_STATUS_RELEASED",
                                                        "RESERVATION_STATUS_EXPIRED",
                                                        "RESERVATION_STATUS_RESTOCKED")
                                                .contains(r.status())))
            throw new IllegalStateException("Inventory compensation remains incomplete");
        for (var payment : foundPayments) {
            var decision =
                    payments.decide(
                            payment.id(),
                            order.getId(),
                            FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND);
            if (!"REFUNDABLE".equals(decision.fulfillmentState()))
                throw new IllegalStateException("Payment refund decision remains pending");
        }
        store.admitCompensation(
                claim,
                compensated.stream().map(ReservationDto::id).toList(),
                foundPayments.stream().map(PaymentIntentDto::id).toList());
        legacy.finishCancellation(claim);
        cleanup.retryOrder(order.getId(), 100);
        store.finish(claim, true);
    }

    static void validateReservations(UUID orderId, List<ReservationDto> reservations) {
        validateResources(orderId, reservations, ReservationDto::id, ReservationDto::orderId);
        for (var reservation : reservations) {
            if (!canonical(reservation.sku())
                    || !canonical(reservation.warehouseId())
                    || reservation.quantity() < 1
                    || reservation.status() == null
                    || !Set.of(
                                    "RESERVATION_STATUS_ACTIVE",
                                    "RESERVATION_STATUS_COMMITTED",
                                    "RESERVATION_STATUS_RELEASED",
                                    "RESERVATION_STATUS_EXPIRED",
                                    "RESERVATION_STATUS_RESTOCKED")
                            .contains(reservation.status()))
                throw new IllegalStateException("Malformed lifecycle inventory metadata");
        }
    }

    private static boolean canonical(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.strip());
    }

    static <T> void validateResources(
            UUID orderId, List<T> resources, Function<T, UUID> id, Function<T, UUID> owner) {
        if (resources == null || resources.size() > 100)
            throw new IllegalStateException("Invalid complete lifecycle discovery result");
        var identities = new HashSet<UUID>();
        for (T resource : resources) {
            if (resource == null
                    || id.apply(resource) == null
                    || !orderId.equals(owner.apply(resource))
                    || !identities.add(id.apply(resource)))
                throw new IllegalStateException("Invalid complete lifecycle discovery result");
        }
    }
}

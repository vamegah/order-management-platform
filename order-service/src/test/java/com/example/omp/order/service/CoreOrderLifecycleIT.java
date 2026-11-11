package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.OrderStatus;
import com.example.omp.order.domain.ShippingAddress;
import com.example.omp.proto.payment.FulfillmentAction;
import com.example.omp.shared.domain.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real migrations, aggregate/outbox transactions, worker leases and integrity constraints. */
@Testcontainers
class CoreOrderLifecycleIT {
    @Test
    void aConflictingHistoricalPointerIsRepairedWithoutCancellingRecoverableInventory() {
        UUID foreignHold = UUID.randomUUID(), foreign = create(foreignHold);
        UUID id = create(UUID.randomUUID()), original = UUID.randomUUID();
        com.example.omp.order.LegacyReferenceFixture.capture(jdbc, id);
        jdbc.update(
                "UPDATE orders.order_line SET reservation_id = ? WHERE order_id = ?",
                foreignHold,
                id);
        jdbc.update(
                "UPDATE orders.lifecycle_watch SET next_scan_at = clock_timestamp() + interval '1 day' WHERE order_id = ?",
                foreign);
        var proof = hold(id, original, "RESERVATION_STATUS_ACTIVE");
        when(inventory.findByOrder(id)).thenReturn(List.of(proof));
        when(inventory.get(original)).thenReturn(proof);
        var pending = payment(id, "PAYMENT_STATUS_PENDING", "OPEN");
        when(payments.get(pending.id())).thenReturn(pending);
        due(id);
        worker.retryDue();
        var repaired = orders.findByIdFull(id);
        assertThat(repaired.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(repaired.getLines().getFirst().getReservationId()).isEqualTo(original);
        assertThat(repaired.getLines().getFirst().isReservationVerified()).isTrue();
        assertThat(orders.findByIdFull(foreign).getLines().getFirst().getReservationId())
                .isEqualTo(foreignHold);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT previous_reservation_id FROM orders.lifecycle_reference_audit WHERE order_id = ?",
                                UUID.class,
                                id))
                .isEqualTo(foreignHold);
        verify(inventory, never()).compensateOrder(any());
        verify(payments, never()).decide(any(), any(), any());
    }

    @Container static final PostgreSQLContainer<?> POSTGRES = OrderPersistenceIT.POSTGRES;

    @Configuration
    @Import({
        OrderPersistenceIT.Beans.class,
        OrderCleanupQueue.class,
        OrderLifecycleStore.class,
        OrderLegacyLifecycleStore.class,
        OrderLegacyLifecycleResolver.class,
        OrderLifecycleCoordinator.class
    })
    static class Beans {
        @Bean
        PaymentClient payments() {
            return mock(PaymentClient.class);
        }

        @Bean
        MeterRegistry metrics() {
            return new SimpleMeterRegistry();
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static OrderTransactionService orders;
    private static OrderLifecycleStore store;
    private static OrderCleanupQueue cleanup;
    private static OrderLifecycleCoordinator worker;
    private static InventoryClient inventory;
    private static PaymentClient payments;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext(Beans.class);
        jdbc = context.getBean(JdbcTemplate.class);
        orders = context.getBean(OrderTransactionService.class);
        store = context.getBean(OrderLifecycleStore.class);
        cleanup = context.getBean(OrderCleanupQueue.class);
        worker = context.getBean(OrderLifecycleCoordinator.class);
        inventory = context.getBean(InventoryClient.class);
        payments = context.getBean(PaymentClient.class);
        transactions = context.getBean(TransactionTemplate.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @BeforeEach
    void isolate() {
        reset(inventory, payments);
        // Isolate manually driven lease tests; natural scheduler timing is covered separately.
        jdbc.update(
                "UPDATE orders.lifecycle_watch SET next_scan_at = clock_timestamp() + interval '1 day'");
    }

    private UUID create(UUID reservation) {
        UUID id = UUID.randomUUID();
        context.getBean(CheckoutJournal.class).start(id);
        orders.create(
                id,
                new CustomerDto(
                        UUID.randomUUID(),
                        "owner@example.test",
                        "A",
                        "B",
                        "ACTIVE",
                        List.of(),
                        null),
                new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                List.of(
                        new OrderTransactionService.OrderLineDraft(
                                UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1)),
                UUID.randomUUID(),
                List.of(new OrderTransactionService.ReservedLine(reservation, "WH-1")));
        return id;
    }

    private void due(UUID id) {
        store.retry(id, "operator", "Verify durable reconciliation");
    }

    private OrderLifecycleStore.Claim claim(UUID id) {
        due(id);
        return store.claim(10).stream()
                .filter(c -> c.orderId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private ReservationDto hold(UUID id, UUID reservation, String status) {
        return new ReservationDto(reservation, "SKU", "WH-1", 1, id, status, 0, 0);
    }

    private PaymentIntentDto payment(UUID id, String status, String decision) {
        var order = orders.findByIdFull(id);
        return new PaymentIntentDto(
                order.getPaymentId(),
                id,
                order.getCustomerId(),
                "10.00",
                "USD",
                status,
                "pi-test",
                0,
                0,
                decision);
    }

    private void arrangeCancellation(UUID id, UUID reservation) {
        var payment = payment(id, "PAYMENT_STATUS_CANCELLED", "REFUNDABLE");
        var released = hold(id, reservation, "RESERVATION_STATUS_RELEASED");
        when(inventory.findByOrder(id)).thenReturn(List.of(released));
        when(inventory.compensateOrder(id))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return List.of(released);
                        });
        when(inventory.get(reservation)).thenReturn(released);
        when(payments.findByOrder(id)).thenReturn(List.of(payment));
        when(payments.decide(eq(payment.id()), eq(id), any())).thenReturn(payment);
        when(payments.get(payment.id())).thenReturn(payment);
    }

    @Test
    void checkoutAtomicallyAdmitsTheWatchAndVerifiedReferences() {
        UUID reservation = UUID.randomUUID(), id = create(reservation);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                                String.class,
                                id))
                .isEqualTo("ACCEPTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.lifecycle_watch WHERE order_id = ?",
                                Long.class,
                                id))
                .isOne();
        assertThat(orders.findByIdFull(id).getLines().getFirst().isReservationVerified()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.reservation_reference_claim WHERE reservation_id = ? AND order_id = ?",
                                Long.class,
                                reservation,
                                id))
                .isOne();
    }

    @Test
    void futureOrdersCannotLoseOrFakeAReferenceAtCommit() {
        UUID id = create(UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE orders.order_line SET reservation_id = NULL WHERE order_id = ?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE orders.order_line SET reservation_verified = false WHERE order_id = ?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(orders.findByIdFull(id).getLines().getFirst().isReservationVerified()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.legacy_lifecycle_resolution WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void aReservationCannotBeSharedByConcurrentCheckouts() throws Exception {
        UUID reservation = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var calls = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++)
                calls.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        create(reservation);
                                        return true;
                                    } catch (RuntimeException expected) {
                                        return false;
                                    }
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    calls.get(0).get(20, TimeUnit.SECONDS),
                                    calls.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM orders.order_line WHERE reservation_id = ?",
                                    Long.class,
                                    reservation))
                    .isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleLeaseCannotAdmitAnyCleanupOrAcknowledgeCancellation() {
        UUID id = create(UUID.randomUUID());
        var claim = claim(id);
        assertThat(store.claim(10)).noneMatch(c -> c.orderId().equals(id));
        store.requestCancellation(claim, "Required inventory expired");
        jdbc.update(
                "UPDATE orders.lifecycle_watch SET lease_id = ? WHERE order_id = ?",
                UUID.randomUUID(),
                id);
        assertThatThrownBy(
                        () ->
                                store.admitCompensation(
                                        claim,
                                        List.of(UUID.randomUUID()),
                                        List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
        assertThat(store.finish(claim, true)).isFalse();
    }

    @Test
    void refundCleanupWaitsForInventoryCompensationAdmission() {
        UUID reservation = UUID.randomUUID(), id = create(reservation);
        var claim = claim(id);
        store.requestCancellation(claim, "Customer cancellation");
        cleanup.enqueue(id, "RESERVATION", reservation);
        cleanup.enqueue(id, "PAYMENT", orders.findByIdFull(id).getPaymentId());
        assertThat(cleanup.retryOrder(id, 100)).isZero();
        verifyNoInteractions(inventory, payments);
        arrangeCancellation(id, reservation);
        // Resume the same work through a new current lease after an explicit operator retry.
        jdbc.update(
                "UPDATE orders.lifecycle_watch SET lease_id = NULL, lease_until = NULL WHERE order_id = ?",
                id);
        due(id);
        worker.retryDue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT compensation_ready FROM orders.lifecycle_watch WHERE order_id = ?",
                                Boolean.class,
                                id))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE order_id = ? AND NOT completed",
                                Long.class,
                                id))
                .isZero();
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void expiredAcceptedInventoryAutomaticallyRequestsCancellationAndCleanup() {
        UUID reservation = UUID.randomUUID(), id = create(reservation);
        arrangeCancellation(id, reservation);
        due(id);
        worker.retryDue();
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventory).compensateOrder(id);
        verify(payments)
                .decide(any(), eq(id), eq(FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attention FROM orders.lifecycle_watch WHERE order_id = ?",
                                Boolean.class,
                                id))
                .isFalse();
    }

    @Test
    void partialFulfillmentIsCancelledOnlyAfterAuthoritativeCompensation() {
        UUID reservation = UUID.randomUUID(), id = create(reservation);
        orders.findAndApply(
                id,
                order -> {
                    order.place("operator");
                    order.confirm("operator", "Paid");
                    order.requestShipment("operator", "Ship");
                });
        arrangeCancellation(id, reservation);
        due(id);
        worker.retryDue();
        var order = orders.findByIdFull(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory().stream().map(h -> h.getToStatus()))
                .contains(OrderStatus.CANCELLING, OrderStatus.CANCELLED);
    }

    @Test
    void conflictingResourceAdmissionRollsBackAllTasksAndTheReadyFlag() {
        UUID id = create(UUID.randomUUID()),
                foreign = UUID.randomUUID(),
                resource = UUID.randomUUID();
        cleanup.enqueue(foreign, "PAYMENT", resource);
        var claim = claim(id);
        store.requestCancellation(claim, "Cancel");
        assertThatThrownBy(
                        () ->
                                store.admitCompensation(
                                        claim, List.of(UUID.randomUUID()), List.of(resource)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT compensation_ready FROM orders.lifecycle_watch WHERE order_id = ?",
                                Boolean.class,
                                id))
                .isFalse();
    }

    @Test
    void retryPreservesAnActiveLeaseAndAuditsTheVerifiedActor() {
        UUID id = create(UUID.randomUUID());
        var claim = claim(id);
        store.retry(id, "admin-subject", " Retry after resolving inventory ");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_id FROM orders.lifecycle_watch WHERE order_id = ?",
                                UUID.class,
                                id))
                .isEqualTo(claim.leaseId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.lifecycle_retry_audit WHERE order_id = ? AND actor = 'admin-subject' AND reason = 'Retry after resolving inventory'",
                                Long.class,
                                id))
                .isOne();
        assertThatThrownBy(() -> store.retry(id, "", "reason"))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
    }

    @Test
    void theActualSeededActiveLegacyOrderReceivesAnAuditedExceptionAndDurableCleanup() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000302");
        var before = orders.findByIdFull(id);
        assertThat(before.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(before.getLines())
                .allSatisfy(
                        l -> {
                            assertThat(l.getReservationId()).isNull();
                            assertThat(l.getReservationWarehouseId()).isNull();
                        });
        when(inventory.findByOrder(id)).thenReturn(List.of());
        when(inventory.compensateOrder(id)).thenReturn(List.of());
        when(payments.findByOrder(id)).thenReturn(List.of());
        due(id);
        worker.retryDue();
        var after = orders.findByIdFull(id);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.getLines())
                .allSatisfy(
                        l -> {
                            assertThat(l.getReservationId()).isNull();
                            assertThat(l.getReservationWarehouseId()).isNull();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resolution FROM orders.legacy_lifecycle_resolution WHERE order_id = ? AND resolved_at IS NOT NULL",
                                String.class,
                                id))
                .isEqualTo("AUDITED_TERMINAL_DISPOSITION");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.terminal_reservation_reference_disposition WHERE order_id = ? AND actor = 'system:legacy-lifecycle'",
                                Long.class,
                                id))
                .isEqualTo(after.getLines().size());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.lifecycle_reference_audit WHERE order_id = ?",
                                Long.class,
                                id))
                .isEqualTo(after.getLines().size());
        long version = after.getVersion();
        due(id);
        worker.retryDue();
        assertThat(orders.findByIdFull(id).getVersion()).isEqualTo(version);
    }
}

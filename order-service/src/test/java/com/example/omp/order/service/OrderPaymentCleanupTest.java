package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.*;
import com.example.omp.order.clients.grpc.dto.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

class OrderPaymentCleanupTest {
    private final UUID order = UUID.randomUUID(),
            payment = UUID.randomUUID(),
            task = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PaymentClient payments = mock(PaymentClient.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private OrderCleanupQueue queue;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<OrderCleanupQueue.Task>>any(),
                        any(),
                        eq(order),
                        eq(10)))
                .thenReturn(
                        List.of(new OrderCleanupQueue.Task(task, order, "PAYMENT", payment, 1)));
        when(jdbc.update(contains("completed = true"), eq(task), any())).thenReturn(1);
        queue =
                new OrderCleanupQueue(
                        jdbc, new TransactionTemplate(manager), inventory, payments, metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "PARTIALLY_REFUNDED"})
    void confirmedPaymentsRefundInsteadOfCancelAndOnlyAcknowledgeTerminalPayment(String status) {
        when(payments.get(payment)).thenReturn(payment(status), payment("REFUNDED"));
        when(payments.refundRemaining(payment, "order-cleanup-" + task))
                .thenReturn(new RefundDto(UUID.randomUUID(), payment, "REFUND_STATUS_COMPLETED"));
        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);
        var sequence = inOrder(manager, payments, jdbc);
        sequence.verify(manager).commit(any());
        sequence.verify(payments).get(payment);
        sequence.verify(payments).refundRemaining(payment, "order-cleanup-" + task);
        sequence.verify(payments).get(payment);
        sequence.verify(jdbc).update(contains("completed = true"), eq(task), any());
        verify(payments, never()).cancel(any(), any());
    }

    @Test
    void pendingRefundKeepsTaskAndRetriesWithSameKey() {
        when(payments.get(payment))
                .thenReturn(payment("CONFIRMED"), payment("CONFIRMED"), payment("REFUNDED"));
        UUID refundId = UUID.randomUUID();
        when(payments.refundRemaining(payment, "order-cleanup-" + task))
                .thenReturn(new RefundDto(refundId, payment, "REFUND_STATUS_PENDING"))
                .thenReturn(new RefundDto(refundId, payment, "REFUND_STATUS_COMPLETED"));
        assertThat(queue.retryOrder(order, 10)).isZero();
        verify(jdbc, never()).update(contains("completed = true"), any(), any());
        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);
        verify(payments, times(2)).refundRemaining(payment, "order-cleanup-" + task);
        assertThat(metrics.get("order.cleanup.failures").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "FAILED", "REFUNDED"})
    void alreadyResolvedPaymentAcknowledgesWithoutMutatingIt(String status) {
        when(payments.get(payment)).thenReturn(payment(status));
        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);
        verify(payments, never()).cancel(any(), any());
        verify(payments, never()).refundRemaining(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "order"})
    void mismatchedPaymentIdentityNeverMutatesRemoteState(String mismatch) {
        when(payments.get(payment))
                .thenReturn(
                        new PaymentIntentDto(
                                mismatch.equals("id") ? UUID.randomUUID() : payment,
                                mismatch.equals("order") ? UUID.randomUUID() : order,
                                UUID.randomUUID(),
                                "10",
                                "USD",
                                "PAYMENT_STATUS_CONFIRMED",
                                "pi-1",
                                0,
                                0));
        assertThat(queue.retryOrder(order, 10)).isZero();
        verify(payments, never()).refundRemaining(any(), any());
        verify(payments, never()).cancel(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "UNSPECIFIED", "wrong-payment", "still-confirmed"})
    void unresolvedOrMismatchedRefundCannotCompleteTask(String outcome) {
        when(payments.get(payment)).thenReturn(payment("CONFIRMED"));
        when(payments.refundRemaining(any(), any()))
                .thenReturn(
                        new RefundDto(
                                UUID.randomUUID(),
                                outcome.equals("wrong-payment") ? UUID.randomUUID() : payment,
                                "REFUND_STATUS_"
                                        + (outcome.contains("-") ? "COMPLETED" : outcome)));
        assertThat(queue.retryOrder(order, 10)).isZero();
        verify(jdbc, never()).update(contains("completed = true"), any(), any());
    }

    @Test
    void cancellationRaceLeavesTaskForPaymentAwareRetry() {
        when(payments.get(payment)).thenReturn(payment("PENDING"));
        when(payments.cancel(payment.toString(), "Order cleanup"))
                .thenThrow(new IllegalStateException("confirmation won"));
        assertThat(queue.retryOrder(order, 10)).isZero();
        verify(jdbc, never()).update(contains("completed = true"), any(), any());
    }

    @Test
    void ambientTransactionsAndInterruptedThreadsCannotStartRpcWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> queue.retryOrder(order, 10))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        Thread.currentThread().interrupt();
        try {
            assertThat(queue.retryOrder(order, 10)).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(jdbc, payments, inventory);
    }

    private PaymentIntentDto payment(String status) {
        return new PaymentIntentDto(
                payment,
                order,
                UUID.randomUUID(),
                "10",
                "USD",
                "PAYMENT_STATUS_" + status,
                "pi-1",
                0,
                0);
    }
}

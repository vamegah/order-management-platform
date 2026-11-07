package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OrderReservationCleanupTest {
    private final UUID order = UUID.randomUUID(),
            reservation = UUID.randomUUID(),
            task = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
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
                        List.of(
                                new OrderCleanupQueue.Task(
                                        task, order, "RESERVATION", reservation, 1)));
        when(jdbc.update(contains("completed = true"), eq(task), any())).thenReturn(1);
        queue =
                new OrderCleanupQueue(
                        jdbc,
                        new TransactionTemplate(manager),
                        inventory,
                        mock(PaymentClient.class),
                        metrics);
    }

    @Test
    void activeReservationReleasesOutsideTransactionAndAcknowledgesVerifiedOutcome() {
        when(inventory.get(reservation)).thenReturn(reservation("ACTIVE"));
        when(inventory.release(reservation.toString())).thenReturn(reservation("RELEASED"));

        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);

        var sequence = inOrder(manager, inventory, jdbc);
        sequence.verify(manager).commit(any());
        sequence.verify(inventory).get(reservation);
        sequence.verify(inventory).release(reservation.toString());
        sequence.verify(jdbc).update(contains("completed = true"), eq(task), any());
        assertThat(metrics.get("order.cleanup.completed").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASED", "EXPIRED"})
    void alreadyResolvedReservationAcknowledgesWithoutMutation(String status) {
        when(inventory.get(reservation)).thenReturn(reservation(status));

        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);
        verify(inventory, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "order"})
    void mismatchedIdentityNeverReleasesAnotherReservation(String mismatch) {
        when(inventory.get(reservation)).thenReturn(mismatched(mismatch));

        assertPending();
        verify(inventory, never()).release(any());
    }

    @Test
    void missingReservationRemainsPendingWithoutMutation() {
        assertPending();
        verify(inventory, never()).release(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"COMMITTED", "UNSPECIFIED", "UNRECOGNIZED"})
    void unsupportedStateRemainsPendingWithoutMutation(String status) {
        when(inventory.get(reservation)).thenReturn(reservation(status));

        assertPending();
        verify(inventory, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "order"})
    void mismatchedReleaseResponseCannotAcknowledgeCleanup(String mismatch) {
        when(inventory.get(reservation)).thenReturn(reservation("ACTIVE"));
        when(inventory.release(reservation.toString())).thenReturn(mismatched(mismatch));

        assertPending();
        verify(inventory).release(reservation.toString());
    }

    @Test
    void missingReleaseResponseCannotAcknowledgeCleanup() {
        when(inventory.get(reservation)).thenReturn(reservation("ACTIVE"));

        assertPending();
        verify(inventory).release(reservation.toString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ACTIVE", "COMMITTED", "UNSPECIFIED", "UNRECOGNIZED"})
    void unresolvedReleaseCannotAcknowledgeCleanup(String status) {
        when(inventory.get(reservation)).thenReturn(reservation("ACTIVE"));
        when(inventory.release(reservation.toString())).thenReturn(reservation(status));

        assertPending();
    }

    @Test
    void lostReleaseResponseRetriesByReadingTerminalStateWithoutReleasingAgain() {
        when(inventory.get(reservation)).thenReturn(reservation("ACTIVE"), reservation("RELEASED"));
        when(inventory.release(reservation.toString()))
                .thenThrow(new IllegalStateException("response lost"));

        assertPending();
        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);
        verify(inventory, times(1)).release(reservation.toString());
        verify(jdbc).update(contains("completed = true"), eq(task), any());
    }

    @Test
    void expiryDuringReleaseAcknowledgesVerifiedExpiredReservation() {
        when(inventory.get(reservation)).thenReturn(reservation("ACTIVE"));
        when(inventory.release(reservation.toString())).thenReturn(reservation("EXPIRED"));

        assertThat(queue.retryOrder(order, 10)).isEqualTo(1);
    }

    private void assertPending() {
        assertThat(queue.retryOrder(order, 10)).isZero();
        verify(jdbc, never()).update(contains("completed = true"), any(), any());
        verify(jdbc)
                .update(
                        contains("next_attempt_at = now()"),
                        eq("IllegalStateException"),
                        eq(task),
                        any());
        assertThat(metrics.get("order.cleanup.failures").counter().count()).isEqualTo(1);
    }

    private ReservationDto reservation(String status) {
        return new ReservationDto(
                reservation,
                "SKU",
                "WH-1",
                1,
                order,
                status == null ? null : "RESERVATION_STATUS_" + status,
                0,
                0);
    }

    private ReservationDto mismatched(String mismatch) {
        return new ReservationDto(
                mismatch.equals("id") ? UUID.randomUUID() : reservation,
                "SKU",
                "WH-1",
                1,
                mismatch.equals("order") ? UUID.randomUUID() : order,
                "RESERVATION_STATUS_RELEASED",
                0,
                0);
    }
}

package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.*;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckoutRecoveryJobTest {
    @TempDir Path directory;

    @Test
    void discoversLostReservationAndRotatesIdentityWithoutLeakingScope() throws Exception {
        var journal = mock(CheckoutJournal.class);
        var cleanup = mock(OrderCleanupQueue.class);
        var inventory = mock(InventoryClient.class);
        var payments = mock(PaymentClient.class);
        var attempt = new CheckoutJournal.Attempt(UUID.randomUUID(), UUID.randomUUID());
        when(journal.claim(10)).thenReturn(List.of(attempt));
        var reservation = mock(ReservationDto.class);
        UUID reservationId = UUID.randomUUID();
        when(reservation.id()).thenReturn(reservationId);
        List<String> identities = new ArrayList<>();
        when(inventory.findByOrder(attempt.orderId()))
                .thenAnswer(
                        invocation -> {
                            identities.add(ServiceTokenScope.current());
                            return List.of(reservation);
                        });
        when(payments.findByOrder(attempt.orderId())).thenReturn(List.of());
        var token = directory.resolve("token");
        Files.writeString(token, "first.payload.signature");
        var job =
                new CheckoutRecoveryJob(
                        journal, cleanup, inventory, payments, new SimpleMeterRegistry(), token);
        job.run();
        assertThat(ServiceTokenScope.current()).isNull();
        Files.writeString(token, "rotated.payload.signature");
        job.run();
        assertThat(identities)
                .containsExactly("first.payload.signature", "rotated.payload.signature");
        verify(cleanup, times(2)).enqueue(attempt.orderId(), "RESERVATION", reservationId);
        verify(journal, times(2)).finish(attempt, true);
        verify(cleanup, times(2)).retryDue();
        assertThat(ServiceTokenScope.current()).isNull();
    }

    @Test
    void lookupFailureRetainsAttemptAndStillRetriesExistingCleanup() throws Exception {
        var journal = mock(CheckoutJournal.class);
        var cleanup = mock(OrderCleanupQueue.class);
        var inventory = mock(InventoryClient.class);
        var payments = mock(PaymentClient.class);
        var attempt = new CheckoutJournal.Attempt(UUID.randomUUID(), UUID.randomUUID());
        when(journal.claim(10)).thenReturn(List.of(attempt));
        when(inventory.findByOrder(attempt.orderId()))
                .thenThrow(new IllegalStateException("Unavailable"));
        var token = directory.resolve("token");
        Files.writeString(token, "service.payload.signature");
        var metrics = new SimpleMeterRegistry();
        new CheckoutRecoveryJob(journal, cleanup, inventory, payments, metrics, token).run();
        verify(journal).finish(attempt, false);
        verify(cleanup).retryDue();
        assertThat(metrics.get("order.recovery.failures").counter().count()).isEqualTo(1);
        assertThat(ServiceTokenScope.current()).isNull();
    }

    @Test
    void malformedIdentityNeverClaimsWorkOrCallsDownstream() throws Exception {
        var journal = mock(CheckoutJournal.class);
        var cleanup = mock(OrderCleanupQueue.class);
        var inventory = mock(InventoryClient.class);
        var payments = mock(PaymentClient.class);
        var token = directory.resolve("token");
        Files.writeString(token, "bad\nAuthorization: injected");
        var metrics = new SimpleMeterRegistry();
        new CheckoutRecoveryJob(journal, cleanup, inventory, payments, metrics, token).run();
        verifyNoInteractions(journal, cleanup, inventory, payments);
        assertThat(metrics.get("order.recovery.worker.failures").counter().count()).isEqualTo(1);
        assertThat(ServiceTokenScope.current()).isNull();
    }
}

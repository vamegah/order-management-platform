package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.*;
import com.example.omp.shared.domain.Money;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentCancellationTest {
    Payment payment() {
        var p = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("15"), "key");
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setProvider("stripe");
        return p;
    }

    @Test
    void beforeDispatchCancellationIsAtomicAndIdempotent() {
        var p = payment();
        var repository = mock(PaymentRepository.class);
        var events = mock(PaymentEventPublisher.class);
        when(repository.lockForRefund(p.getId())).thenReturn(Optional.of(p));
        var store = new PaymentCancellationStore(repository, events);
        assertThat(store.prepare(p.getId(), "stripe", "Owner requested").getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        store.prepare(p.getId(), "stripe", "Owner requested");
        verify(events, times(1)).publishCancelled(p, "Owner requested");
    }

    @Test
    void uncertainInFlightChargeCannotBeDeclaredCancelled() {
        var p = payment();
        p.setPaymentMethodId("pm_bound");
        p.setChargeStartedAt(java.time.Instant.now());
        var repository = mock(PaymentRepository.class);
        var events = mock(PaymentEventPublisher.class);
        when(repository.lockForRefund(p.getId())).thenReturn(Optional.of(p));
        var store = new PaymentCancellationStore(repository, events);
        var provider = mock(PaymentProvider.class);
        when(provider.providerName()).thenReturn("stripe");
        assertThatThrownBy(
                        () ->
                                new PaymentCancellationService(store, provider)
                                        .cancel(p.getId(), "Owner requested"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.isCancellationRequested()).isTrue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(provider, never()).cancelCharge(any());
        verifyNoInteractions(events);
    }

    @Test
    void externalCancellationIsVerifiedOutsideTheDatabaseTransaction() {
        var p = payment();
        p.setPaymentMethodId("pm_bound");
        p.setProviderReference("pi_bound");
        p.setChargeStartedAt(java.time.Instant.now());
        var repository = mock(PaymentRepository.class);
        var events = mock(PaymentEventPublisher.class);
        when(repository.lockForRefund(p.getId())).thenReturn(Optional.of(p));
        var store = new PaymentCancellationStore(repository, events);
        var provider = mock(PaymentProvider.class);
        when(provider.providerName()).thenReturn("stripe");
        when(provider.cancelCharge(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            ChargeRequest request = call.getArgument(0);
                            assertThat(request.providerReference()).isEqualTo("pi_bound");
                            return new ProviderChargeResult(
                                    ProviderChargeResult.Status.DECLINED,
                                    "pi_bound",
                                    "provider_canceled",
                                    null,
                                    null);
                        });
        assertThat(
                        new PaymentCancellationService(store, provider)
                                .cancel(p.getId(), "Owner requested")
                                .getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        verify(events).publishCancelled(p, "Owner requested");
    }

    @Test
    void aCaptureThatWinsTheRaceRequiresRefundInsteadOfFalseCancellation() {
        var p = payment();
        p.setPaymentMethodId("pm_bound");
        p.setProviderReference("pi_bound");
        var repository = mock(PaymentRepository.class);
        var events = mock(PaymentEventPublisher.class);
        when(repository.lockForRefund(p.getId())).thenReturn(Optional.of(p));
        var provider = mock(PaymentProvider.class);
        when(provider.providerName()).thenReturn("stripe");
        when(provider.cancelCharge(any())).thenReturn(ProviderChargeResult.succeeded("pi_bound"));
        assertThatThrownBy(
                        () ->
                                new PaymentCancellationService(
                                                new PaymentCancellationStore(repository, events),
                                                provider)
                                        .cancel(p.getId(), "Requested"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        verify(events).publishConfirmed(p);
        verify(events, never()).publishCancelled(any(), any());
    }
}

package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.outbox.OutboxWriter;
import com.example.omp.payment.provider.ProviderChargeResult;
import com.example.omp.payment.repository.*;
import com.example.omp.payment.service.*;
import com.example.omp.shared.domain.Money;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderCaptureReconciliationTest {
    Payment failed(String changed) {
        var p = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("15"), "charge-key");
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setProvider("provider".equals(changed) ? "noop" : "stripe");
        if (!"method".equals(changed)) p.setPaymentMethodId("pm_bound");
        p.setProviderReference("pi_bound");
        p.markFailed("older_decline", "Earlier failed notification");
        return p;
    }

    @Test
    void verifiedCaptureCanSupersedeAnOlderFailureWithoutOpeningTheOrderFence() {
        var p = failed("");
        p.decideFulfillment("CANCEL");
        p.decideFulfillment("ALLOW_REFUND");
        assertThatThrownBy(() -> p.confirm("pi_bound"))
                .isInstanceOf(com.example.omp.payment.error.PaymentStateException.class);
        p.reconcileProviderCapture("pi_bound");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(p.getCapturedAt()).isNotNull();
        assertThat(p.getFailureCode()).isNull();
        assertThat(p.getFulfillmentState()).isEqualTo("REFUNDABLE");
        assertThatThrownBy(() -> p.decideFulfillment("BEGIN"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "method", "reference"})
    void reconciliationCannotUseAnUnboundProviderOrIntent(String changed) {
        var p = failed(changed);
        assertThatThrownBy(
                        () ->
                                p.reconcileProviderCapture(
                                        "reference".equals(changed) ? "pi_other" : "pi_bound"))
                .isInstanceOf(com.example.omp.payment.error.PaymentStateException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void captureReplyAndRepeatedReplyWriteOneConfirmationAndOneCompensation() {
        var p = failed("");
        var repository = mock(PaymentRepository.class);
        when(repository.lockForRefund(p.getId())).thenReturn(Optional.of(p));
        var outbox = mock(OutboxWriter.class);
        var refunds = mock(RefundStore.class);
        var ops =
                new PaymentTransactionalOps(
                        repository,
                        outbox,
                        mock(PaymentCreationLock.class),
                        mock(com.example.omp.payment.grpc.handler.PaymentEventStreamHandler.class),
                        refunds);
        ops.applyChargeResult(p.getId(), ProviderChargeResult.succeeded("pi_bound"));
        ops.applyChargeResult(p.getId(), ProviderChargeResult.succeeded("pi_bound"));
        verify(outbox, times(1)).write(eq("payment.confirmed"), eq(p.getId()), any());
        verify(refunds, times(1)).compensateReconciledCapture(p);
    }

    @Test
    void compensationWaitsForTheInventoryFenceAndDoesNotTouchProviderIo() {
        var p = failed("");
        p.decideFulfillment("CANCEL");
        p.reconcileProviderCapture("pi_bound");
        var payments = mock(PaymentRepository.class);
        var refunds = mock(RefundRepository.class);
        var store =
                new RefundStore(
                        payments,
                        refunds,
                        mock(com.example.omp.payment.events.PaymentEventPublisher.class),
                        mock(PaymentCreationLock.class));
        assertThatThrownBy(() -> store.compensateReconciledCapture(p))
                .hasMessage("Late capture awaits inventory compensation");
        verifyNoInteractions(payments, refunds);
    }
}

package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.repository.*;
import com.example.omp.payment.service.*;
import com.example.omp.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual recovery/store/domain behavior with controlled persistence and provider boundaries. */
class RefundRecoveryTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PaymentEventPublisher events = mock(PaymentEventPublisher.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final Map<UUID, Payment> paymentRows = new HashMap<>();
    private final Map<UUID, Refund> refundRows = new LinkedHashMap<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final RefundStore store =
            new RefundStore(payments, refunds, events, mock(PaymentCreationLock.class));
    private final RefundRecoveryService recovery =
            new RefundRecoveryService(store, provider, metrics);

    @BeforeEach
    void setup() {
        when(provider.providerName()).thenReturn("stripe");
        when(payments.lockForRefund(any()))
                .thenAnswer(call -> Optional.ofNullable(paymentRows.get(call.getArgument(0))));
        when(payments.tryLockForRefund(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return Optional.ofNullable(paymentRows.get(call.getArgument(0)));
                        });
        when(refunds.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(refundRows.get(call.getArgument(0))));
        when(refunds.findByIdempotencyKey(any()))
                .thenAnswer(
                        call ->
                                refundRows.values().stream()
                                        .filter(
                                                r ->
                                                        r.getIdempotencyKey()
                                                                .equals(call.getArgument(0)))
                                        .findFirst());
        when(refunds.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            Refund refund = call.getArgument(0);
                            ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
                            ReflectionTestUtils.setField(refund, "createdAt", Instant.now());
                            refundRows.put(refund.getId(), refund);
                            return refund;
                        });
        when(refunds.findDueRecovery())
                .thenAnswer(
                        call ->
                                refundRows.values().stream()
                                        .filter(
                                                r ->
                                                        r.getStatus() == RefundStatus.PENDING
                                                                && r.getAttentionReason() == null
                                                                && r.isDue(Instant.now()))
                                        .map(
                                                r ->
                                                        new RefundRepository.Candidate() {
                                                            public UUID getId() {
                                                                return r.getId();
                                                            }

                                                            public UUID getPaymentId() {
                                                                return r.getPaymentId();
                                                            }
                                                        })
                                        .toList());
        when(refunds.countByStatus(RefundStatus.PENDING))
                .thenAnswer(
                        call ->
                                refundRows.values().stream()
                                        .filter(r -> r.getStatus() == RefundStatus.PENDING)
                                        .count());
        when(refunds.countByStatusAndAttentionReasonIsNotNull(RefundStatus.PENDING))
                .thenAnswer(
                        call ->
                                refundRows.values().stream()
                                        .filter(
                                                r ->
                                                        r.getStatus() == RefundStatus.PENDING
                                                                && r.getAttentionReason() != null)
                                        .count());
    }

    @Test
    void missingWebhookRecoversViaGetAfterReplayWindowWithoutAnotherPost() {
        Refund refund = pending("re_1");
        ReflectionTestUtils.setField(refund, "createdAt", Instant.now().minusSeconds(21 * 3600));
        when(provider.lookupRefund(any(), eq("re_1")))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return ProviderRefundResult.succeeded("re_1");
                        });
        assertThat(recovery.retry(refund.getPaymentId(), refund.getId()).getStatus())
                .isEqualTo(RefundStatus.COMPLETED);
        assertThat(paymentRows.get(refund.getPaymentId()).getRemainingRefundable().getAmount())
                .isEqualByComparingTo("6");
        assertThat(refund.getRecoveryAttempts()).isEqualTo(1);
        assertThat(refund.getLastError()).isNull();
        recovery.retry(refund.getPaymentId(), refund.getId());
        verify(provider).lookupRefund(any(), eq("re_1"));
        verify(provider, never()).refund(any());
        verify(events).publishRefund(any(), eq(refund));
    }

    @Test
    void uncertainReplyRetriesOnlyTheOriginalProviderKeyAndBacksOff() {
        Refund refund = pending(null);
        when(provider.refund(any()))
                .thenThrow(new IllegalStateException("sensitive account detail"));
        assertThat(recovery.retryDue()).isZero();
        assertThat(refund.getLastError()).isEqualTo("PROVIDER_UNRESOLVED");
        assertThat(refund.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(50));
        assertThat(recovery.retryDue()).isZero();
        verify(provider).refund(any());
        doAnswer(
                        call -> {
                            RefundRequest request = call.getArgument(0);
                            assertThat(request.idempotencyKey()).isEqualTo(refund.getProviderKey());
                            return ProviderRefundResult.succeeded("re_1");
                        })
                .when(provider)
                .refund(any());
        assertThat(recovery.retry(refund.getPaymentId(), refund.getId()).getStatus())
                .isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getRecoveryAttempts()).isEqualTo(2);
        assertThat(refund.getLastError()).isNull();
    }

    @Test
    void pendingLookupPreservesBalanceAndSchedulesAnotherRead() {
        Refund refund = pending("re_1");
        when(provider.lookupRefund(any(), any())).thenReturn(ProviderRefundResult.pending("re_1"));
        assertThat(recovery.retryDue()).isZero();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(recovery.retryDue()).isZero();
        verify(provider).lookupRefund(any(), eq("re_1"));
        verifyNoInteractions(events);
    }

    @Test
    void finalProviderFailureReleasesReservedBalanceWithoutApplyingRefund() {
        Refund refund = pending("re_1");
        when(provider.lookupRefund(any(), any()))
                .thenReturn(ProviderRefundResult.failed("re_1", "private error"));
        assertThat(recovery.retryDue()).isEqualTo(1);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(refund.getReason()).isEqualTo("requested");
        assertThat(paymentRows.get(refund.getPaymentId()).getRemainingRefundable().getAmount())
                .isEqualByComparingTo("10");
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "expired", "provider", "payment-state"})
    void unsafeRetryRequiresAttentionEvenWhenAdministratorForcesIt(String kind) {
        Refund refund = pending(null);
        switch (kind) {
            case "legacy" -> ReflectionTestUtils.setField(refund, "providerKey", null);
            case "expired" ->
                    ReflectionTestUtils.setField(
                            refund, "createdAt", Instant.now().minusSeconds(21 * 3600));
            case "provider" -> paymentRows.get(refund.getPaymentId()).setProvider("noop");
            case "payment-state" ->
                    paymentRows.get(refund.getPaymentId()).applyRefund(Money.usd("10"));
        }
        assertThat(recovery.retry(refund.getPaymentId(), refund.getId()).getStatus())
                .isEqualTo(RefundStatus.PENDING);
        assertThat(refund.getAttentionReason())
                .isEqualTo(
                        switch (kind) {
                            case "legacy" -> "LEGACY_INTENT";
                            case "expired" -> "REPLAY_WINDOW_EXPIRED";
                            case "provider" -> "PROVIDER_MISMATCH";
                            default -> "PAYMENT_STATE_MISMATCH";
                        });
        assertThat(recovery.retryDue()).isZero();
        assertThat(metrics.get("payment.refund.attention").gauge().value()).isEqualTo(1);
        verify(provider, never()).refund(any());
        verify(provider, never()).lookupRefund(any(), any());
    }

    @Test
    void activeLeaseAndBusyPaymentSkipWithoutTouchingProvider() {
        Refund refund = pending(null);
        var active = store.claim(refund.getPaymentId(), refund.getId());
        assertThat(recovery.retry(refund.getPaymentId(), refund.getId()).getStatus())
                .isEqualTo(RefundStatus.PENDING);
        assertThat(refund.ownsAttempt(active.lease())).isTrue();
        when(payments.tryLockForRefund(refund.getPaymentId())).thenReturn(Optional.empty());
        recovery.retry(refund.getPaymentId(), refund.getId());
        verify(provider, never()).refund(any());
        verify(provider, never()).lookupRefund(any(), any());
    }

    @Test
    void malformedLookupIsIsolatedAndDoesNotBlockOtherRefunds() {
        Refund first = pending("re_first"), second = pending("re_second");
        when(provider.lookupRefund(any(), eq("re_first")))
                .thenReturn(ProviderRefundResult.succeeded("re_other"));
        when(provider.lookupRefund(any(), eq("re_second")))
                .thenReturn(ProviderRefundResult.succeeded("re_second"));
        assertThat(recovery.retryDue()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(metrics.get("payment.refund.recovery.failures").counter().count()).isEqualTo(1);
        verify(events).publishRefund(any(), eq(second));
    }

    @Test
    void webhookWinningLookupRaceEmitsExactlyOneEvent() {
        Refund refund = pending("re_1");
        when(provider.lookupRefund(any(), any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            store.webhook(
                                                    refund.getPaymentId(),
                                                    "stripe",
                                                    webhook(refund, "succeeded")))
                                    .isTrue();
                            return ProviderRefundResult.succeeded("re_1");
                        });
        assertThat(recovery.retryDue()).isEqualTo(1);
        verify(events).publishRefund(any(), eq(refund));
        assertThat(paymentRows.get(refund.getPaymentId()).getRemainingRefundable().getAmount())
                .isEqualByComparingTo("6");
    }

    @Test
    void earlyPendingWebhookRecordsReferenceWithoutReleasingLiveLease() {
        Refund refund = pending(null);
        var attempt = store.claim(refund.getPaymentId(), refund.getId());
        assertThat(store.webhook(refund.getPaymentId(), "stripe", webhook(refund, "pending")))
                .isTrue();
        assertThat(refund.getProviderReference()).isEqualTo("re_1");
        assertThat(refund.ownsAttempt(attempt.lease())).isTrue();
        recovery.retry(refund.getPaymentId(), refund.getId());
        verify(provider, never()).lookupRefund(any(), any());
        ReflectionTestUtils.setField(refund, "attemptUntil", Instant.EPOCH);
        when(provider.lookupRefund(any(), any()))
                .thenReturn(ProviderRefundResult.succeeded("re_1"));
        recovery.retry(refund.getPaymentId(), refund.getId());
        verify(provider).lookupRefund(any(), eq("re_1"));
        verify(provider, never()).refund(any());
    }

    @Test
    void staleLookupCannotCompleteOrClearNewLease() {
        Refund refund = pending("re_1");
        var first = store.claimRecovery(refund.getPaymentId(), refund.getId(), "stripe", true);
        ReflectionTestUtils.setField(refund, "attemptUntil", Instant.EPOCH);
        var current = store.claimRecovery(refund.getPaymentId(), refund.getId(), "stripe", true);
        store.complete(first, ProviderRefundResult.succeeded("re_1"));
        assertThat(refund.ownsAttempt(current.lease())).isTrue();
        verifyNoInteractions(events);
    }

    @Test
    void backgroundBatchIsLimitedToTenAndCountsPendingWork() {
        for (int i = 0; i < 11; i++) pending("re_" + i);
        when(provider.lookupRefund(any(), any()))
                .thenAnswer(call -> ProviderRefundResult.succeeded(call.getArgument(1)));
        assertThat(recovery.retryDue()).isEqualTo(10);
        assertThat(metrics.get("payment.refund.pending").gauge().value()).isEqualTo(1);
        verify(provider, times(10)).lookupRefund(any(), any());
    }

    @Test
    void ambientTransactionOrInterruptionCannotStartRecovery() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(recovery::retryDue).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        Thread.currentThread().interrupt();
        try {
            assertThat(recovery.retryDue()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(payments, refunds, provider, events);
    }

    @Test
    void missingRefundOrMismatchedPaymentPathIsNotFoundBeforeProviderAccess() {
        Refund refund = pending("re_1");
        assertThatThrownBy(() -> recovery.inspect(UUID.randomUUID(), refund.getId()))
                .isInstanceOf(com.example.omp.payment.error.PaymentNotFoundException.class);
        assertThatThrownBy(() -> recovery.inspect(refund.getPaymentId(), UUID.randomUUID()))
                .isInstanceOf(com.example.omp.payment.error.PaymentNotFoundException.class);
        verify(provider, never()).refund(any());
        verify(provider, never()).lookupRefund(any(), any());
    }

    private Refund pending(String reference) {
        UUID id = UUID.randomUUID();
        Payment payment =
                new Payment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Money.usd("10"),
                        UUID.randomUUID().toString());
        ReflectionTestUtils.setField(payment, "id", id);
        payment.setProvider("stripe");
        payment.confirm("pi_" + id);
        paymentRows.put(id, payment);
        Refund refund =
                store.prepare(id, 400L, null, "requested", UUID.randomUUID().toString(), "stripe");
        if (reference != null) refund.correlate(reference);
        return refund;
    }

    private ProviderWebhookEvent webhook(Refund refund, String status) {
        var data = new ObjectMapper().createObjectNode();
        data.put(
                        "id",
                        refund.getProviderReference() == null
                                ? "re_1"
                                : refund.getProviderReference())
                .put("status", status)
                .put("amount", 400)
                .put("currency", "usd");
        data.putObject("metadata").put("refund_id", refund.getId().toString());
        return new ProviderWebhookEvent(
                UUID.randomUUID().toString(),
                "refund.updated",
                data,
                paymentRows.get(refund.getPaymentId()).getProviderReference());
    }
}

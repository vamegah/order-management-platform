package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.domain.RefundReconciliationAudit.Outcome;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual financial/audit workflow; database transactions are covered separately by PostgreSQL IT.
 */
class RefundReconciliationTest {
    private final UUID paymentId = UUID.randomUUID(), refundId = UUID.randomUUID();
    private final Payment payment =
            new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"), "charge-key");
    private final Refund refund =
            new Refund(payment, Money.usd("4"), "original reason", "customer-key");
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final RefundReconciliationAuditRepository audits =
            mock(RefundReconciliationAuditRepository.class);
    private final PaymentEventPublisher events = mock(PaymentEventPublisher.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final Map<UUID, RefundReconciliationAudit> rows = new LinkedHashMap<>();
    private final RefundStore state =
            new RefundStore(payments, refunds, events, mock(PaymentCreationLock.class));
    private final RefundReconciliationStore store =
            new RefundReconciliationStore(payments, refunds, audits, state);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final RefundReconciliationService service =
            new RefundReconciliationService(store, provider, metrics);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setProvider("stripe");
        payment.confirm("pi_1");
        ReflectionTestUtils.setField(refund, "id", refundId);
        ReflectionTestUtils.setField(refund, "createdAt", Instant.now().minusSeconds(21 * 3600));
        refund.initializeProviderKey();
        refund.requireAttention(Refund.RecoveryBlock.REPLAY_WINDOW_EXPIRED);
        when(payments.lockForRefund(paymentId)).thenReturn(Optional.of(payment));
        when(refunds.findById(refundId)).thenReturn(Optional.of(refund));
        when(refunds.findByPaymentIdAndProviderReference(any(), any()))
                .thenAnswer(
                        call ->
                                Objects.equals(refund.getProviderReference(), call.getArgument(1))
                                        ? Optional.of(refund)
                                        : Optional.empty());
        when(audits.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            RefundReconciliationAudit audit = call.getArgument(0);
                            ReflectionTestUtils.setField(audit, "id", UUID.randomUUID());
                            rows.put(audit.getId(), audit);
                            return audit;
                        });
        when(audits.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(audits.findTop20ByRefund_IdOrderByCreatedAtDescIdDesc(refundId))
                .thenAnswer(call -> new ArrayList<>(rows.values()));
        when(provider.providerName()).thenReturn("stripe");
        authenticate("operator");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiredUnknownIntentUsesVerifiedGetAndCommitsOneRefundAndAudit() {
        when(provider.lookupRefund(any(), eq("re_candidate"), eq(refundId)))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(rows.values())
                                    .singleElement()
                                    .satisfies(
                                            a ->
                                                    assertThat(a.getOutcome())
                                                            .isEqualTo(Outcome.IN_PROGRESS));
                            return ProviderRefundResult.succeeded("re_candidate");
                        });
        String key = refund.getProviderKey();
        var result = reconcile();
        assertThat(result.refund().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(result.audit().outcome()).isEqualTo("COMPLETED");
        assertThat(result.audit().actorSubject()).isEqualTo("operator");
        assertThat(result.audit().actorIssuer()).isEqualTo("https://issuer.example");
        assertThat(result.audit().reason()).isEqualTo("ledger investigation");
        assertThat(result.audit().completedAt()).isNotNull();
        assertThat(refund.getProviderKey()).isEqualTo(key);
        assertThat(refund.getId()).isEqualTo(refundId);
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("6");
        assertThat(reconcile().audit().outcome()).isEqualTo("NO_CHANGE");
        verify(provider).lookupRefund(any(), eq("re_candidate"), eq(refundId));
        verify(provider, never()).lookupRefund(any(), any());
        verify(provider, never()).refund(any());
        verify(events).publishRefund(payment, refund);
        assertThat(rows).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "failed"})
    void verifiedNonSuccessOutcomeBindsReferenceWithoutApplyingMoney(String status) {
        when(provider.lookupRefund(any(), any(), any()))
                .thenReturn(
                        status.equals("pending")
                                ? ProviderRefundResult.pending("re_candidate")
                                : ProviderRefundResult.failed("re_candidate", "private"));
        var result = reconcile();
        assertThat(result.audit().outcome()).isEqualTo(status.toUpperCase(Locale.ROOT));
        assertThat(refund.getProviderReference()).isEqualTo("re_candidate");
        assertThat(refund.getAttentionReason()).isNull();
        assertThat(refund.getReason()).isEqualTo("original reason");
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "missing", "error", "wrong-id", "missing-status"})
    void unresolvedCandidateNeverBindsOrReleasesReservedBalance(String mode) {
        when(provider.lookupRefund(any(), any(), any()))
                .thenAnswer(
                        call ->
                                switch (mode) {
                                    case "exception" ->
                                            throw new IllegalStateException(
                                                    "private provider payload");
                                    case "missing" -> null;
                                    case "wrong-id" -> ProviderRefundResult.succeeded("re_other");
                                    case "missing-status" ->
                                            new ProviderRefundResult(null, null, null);
                                    default ->
                                            ProviderRefundResult.error("private provider payload");
                                });
        var result = reconcile();
        assertThat(result.audit().outcome()).isEqualTo("UNRESOLVED");
        assertThat(result.audit().detailCode()).isEqualTo("PROVIDER_UNRESOLVED");
        assertThat(result.audit().candidateReference()).isEqualTo("re_candidate");
        assertThat(refund.getProviderReference()).isNull();
        assertThat(refund.getAttentionReason()).isEqualTo("REPLAY_WINDOW_EXPIRED");
        assertThat(refund.getLastError()).isEqualTo("PROVIDER_UNRESOLVED");
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "provider", "payment", "conflict", "in-use"})
    void blockedCandidateIsAuditedBeforeAnyProviderLookup(String kind) {
        switch (kind) {
            case "legacy" -> ReflectionTestUtils.setField(refund, "providerKey", null);
            case "provider" -> payment.setProvider("noop");
            case "payment" -> payment.applyRefund(Money.usd("10"));
            case "conflict" -> refund.correlate("re_other");
            case "in-use" -> referenceAlreadyUsed();
        }
        var result = reconcile();
        assertThat(result.audit().outcome()).isEqualTo("BLOCKED");
        assertThat(result.audit().detailCode())
                .isEqualTo(
                        switch (kind) {
                            case "legacy" -> "LEGACY_INTENT";
                            case "provider" -> "PROVIDER_MISMATCH";
                            case "payment" -> "PAYMENT_STATE_MISMATCH";
                            case "conflict" -> "REFERENCE_CONFLICT";
                            default -> "REFERENCE_IN_USE";
                        });
        verify(provider, never()).lookupRefund(any(), any(), any());
        verify(provider, never()).refund(any());
        verifyNoInteractions(events);
    }

    @Test
    void liveLeaseIsAuditedBusyAndCannotBeStolen() {
        UUID lease = refund.claimAttempt(Instant.now());
        assertThat(reconcile().audit().outcome()).isEqualTo("BUSY");
        assertThat(refund.ownsAttempt(lease)).isTrue();
        verify(provider, never()).lookupRefund(any(), any(), any());
    }

    @Test
    void staleOperatorReplyCannotOverwriteNewCompletion() {
        var old = begin();
        ReflectionTestUtils.setField(refund, "attemptUntil", Instant.EPOCH);
        when(provider.lookupRefund(any(), any(), any()))
                .thenReturn(ProviderRefundResult.succeeded("re_candidate"));
        reconcile();
        var late = store.complete(old, ProviderRefundResult.succeeded("re_candidate"));
        assertThat(late.audit().outcome()).isEqualTo("SUPERSEDED");
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("6");
        verify(events).publishRefund(payment, refund);
    }

    @Test
    void repeatedFinalizerAndForgedLeaseCannotApplyMoneyTwice() {
        var claim = begin();
        var attempt = claim.attempt();
        var forged =
                new RefundReconciliationStore.Claim(
                        claim.auditId(),
                        new RefundStore.Attempt(
                                paymentId,
                                refundId,
                                UUID.randomUUID(),
                                attempt.request(),
                                attempt.lookupReference()));
        assertThatThrownBy(
                        () ->
                                store.complete(
                                        forged, ProviderRefundResult.succeeded("re_candidate")))
                .isInstanceOf(IllegalArgumentException.class);
        store.complete(claim, ProviderRefundResult.succeeded("re_candidate"));
        store.complete(claim, ProviderRefundResult.succeeded("re_candidate"));
        verify(events).publishRefund(payment, refund);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment", "reference"})
    void conflictDuringProviderReadIsAuditedWithoutOverRefund(String conflict) {
        when(provider.lookupRefund(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            if (conflict.equals("payment")) payment.applyRefund(Money.usd("10"));
                            else referenceAlreadyUsed();
                            return ProviderRefundResult.succeeded("re_candidate");
                        });
        var result = reconcile();
        assertThat(result.audit().outcome()).isEqualTo("BLOCKED");
        assertThat(result.audit().detailCode())
                .isEqualTo(
                        conflict.equals("payment") ? "PAYMENT_STATE_MISMATCH" : "REFERENCE_IN_USE");
        assertThat(refund.getProviderReference()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    void webhookWinningLookupRacePostsOnlyOneFinancialEvent() {
        when(provider.lookupRefund(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            var data = new ObjectMapper().createObjectNode();
                            data.put("id", "re_candidate")
                                    .put("amount", 400)
                                    .put("currency", "usd")
                                    .put("status", "succeeded");
                            data.putObject("metadata").put("refund_id", refundId.toString());
                            assertThat(
                                            state.webhook(
                                                    paymentId,
                                                    "stripe",
                                                    new ProviderWebhookEvent(
                                                            "evt_1",
                                                            "refund.updated",
                                                            data,
                                                            "pi_1")))
                                    .isTrue();
                            return ProviderRefundResult.succeeded("re_candidate");
                        });
        assertThat(reconcile().audit().outcome()).isEqualTo("SUPERSEDED");
        verify(events).publishRefund(payment, refund);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "reference",
                "reason",
                "long-reason",
                "null-reason",
                "nul-reason",
                "subject",
                "long-subject"
            })
    void invalidInputOrActorIsRejectedBeforeCreatingAnAudit(String invalid) {
        if (invalid.equals("subject")) authenticate(" ");
        if (invalid.equals("long-subject")) authenticate("x".repeat(256));
        assertThatThrownBy(
                        () ->
                                service.reconcile(
                                        paymentId,
                                        refundId,
                                        invalid.equals("reference") ? "../refunds" : "re_candidate",
                                        switch (invalid) {
                                            case "reason" -> " ";
                                            case "long-reason" -> "x".repeat(501);
                                            case "null-reason" -> null;
                                            case "nul-reason" -> "review" + Character.toString(0);
                                            default -> "review";
                                        }))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(audits, payments, refunds);
    }

    @Test
    void interruptionOrAmbientTransactionCannotStartFinancialWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(this::reconcile).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(this::reconcile).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(audits, payments, refunds);
    }

    @Test
    void historyIsBoundedAndUnsupportedProviderCannotFallbackToAnUnverifiedRead() {
        var unsupported = mock(PaymentProvider.class, CALLS_REAL_METHODS);
        when(unsupported.providerName()).thenReturn("stripe");
        var service = new RefundReconciliationService(store, unsupported, metrics);
        assertThat(
                        service.reconcile(paymentId, refundId, "re_candidate", "review")
                                .audit()
                                .outcome())
                .isEqualTo("UNRESOLVED");
        verify(unsupported, never()).lookupRefund(any(), any());
        verify(unsupported, never()).refund(any());
        refund.claimAttempt(Instant.now());
        for (int i = 0; i < 25; i++) reconcile();
        assertThat(service.history(paymentId, refundId)).hasSize(20);
    }

    private RefundReconciliationStore.Result reconcile() {
        return service.reconcile(paymentId, refundId, "re_candidate", "ledger investigation");
    }

    private RefundReconciliationStore.Claim begin() {
        return store.begin(
                paymentId,
                refundId,
                "re_candidate",
                "stripe",
                "operator",
                "https://issuer.example",
                "review");
    }

    private void referenceAlreadyUsed() {
        var other = new Refund(payment, Money.usd("4"), null, "other");
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        when(refunds.findByPaymentIdAndProviderReference(paymentId, "re_candidate"))
                .thenReturn(Optional.of(other));
    }

    private void authenticate(String subject) {
        var jwt =
                Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .issuer("https://issuer.example")
                        .subject(subject)
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
    }
}

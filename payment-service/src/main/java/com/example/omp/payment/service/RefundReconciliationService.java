package com.example.omp.payment.service;

import com.example.omp.payment.provider.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RefundReconciliationService {
    private final RefundReconciliationStore store;
    private final PaymentProvider provider;
    private final MeterRegistry metrics;

    public RefundReconciliationService(
            RefundReconciliationStore store, PaymentProvider provider, MeterRegistry metrics) {
        this.store = store;
        this.provider = provider;
        this.metrics = metrics;
    }

    @PreAuthorize("@paymentAccess.isAdministrator(authentication)")
    public RefundReconciliationStore.Result reconcile(
            UUID paymentId, UUID refundId, String reference, String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || Thread.currentThread().isInterrupted())
            throw new IllegalStateException(
                    "Reconciliation requires an uninterrupted caller outside a transaction");
        if (reference == null
                || !reference.matches("re_[A-Za-z0-9_]{1,240}")
                || reason == null
                || reason.isBlank()
                || reason.length() > 500
                || reason.indexOf(0) >= 0)
            throw new IllegalArgumentException("Invalid reconciliation request");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !authentication.isAuthenticated())
            throw new org.springframework.security.access.AccessDeniedException(
                    "Administrator required");
        String subject = token.getToken().getSubject();
        String issuer =
                token.getToken().getIssuer() == null
                        ? null
                        : token.getToken().getIssuer().toString();
        if (subject == null
                || subject.isBlank()
                || subject.length() > 255
                || subject.chars().anyMatch(Character::isISOControl)
                || (issuer != null && issuer.length() > 512))
            throw new IllegalArgumentException("Invalid reconciliation actor");
        var claim =
                store.begin(
                        paymentId,
                        refundId,
                        reference,
                        provider.providerName(),
                        subject,
                        issuer,
                        reason);
        RefundReconciliationStore.Result result;
        if (claim.attempt() == null) result = store.read(paymentId, refundId, claim.auditId());
        else {
            ProviderRefundResult outcome;
            try {
                outcome = provider.lookupRefund(claim.attempt().request(), reference, refundId);
                if (outcome == null || outcome.status() == null)
                    throw new IllegalStateException("Missing provider reconciliation result");
            } catch (RuntimeException failure) {
                outcome = ProviderRefundResult.error("Provider lookup unresolved");
            }
            result = store.complete(claim, outcome);
        }
        metrics.counter("payment.refund.reconciliation", "outcome", result.audit().outcome())
                .increment();
        return result;
    }

    @PreAuthorize("@paymentAccess.isAdministrator(authentication)")
    public List<RefundReconciliationStore.AuditView> history(UUID paymentId, UUID refundId) {
        return store.history(paymentId, refundId);
    }
}

package com.example.omp.payment.service;

import com.example.omp.payment.domain.Refund;
import com.example.omp.payment.domain.RefundStatus;
import com.example.omp.payment.provider.PaymentProvider;
import com.example.omp.payment.provider.ProviderRefundResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded background recovery and administrator retry; provider I/O never joins a DB transaction.
 */
@Service
public class RefundRecoveryService {
    private final RefundStore store;
    private final PaymentProvider provider;
    private final MeterRegistry metrics;

    public RefundRecoveryService(
            RefundStore store, PaymentProvider provider, MeterRegistry metrics) {
        this.store = store;
        this.provider = provider;
        this.metrics = metrics;
        metrics.gauge("payment.refund.pending", store, RefundStore::pendingCount);
        metrics.gauge("payment.refund.attention", store, RefundStore::attentionCount);
    }

    @PreAuthorize("@paymentAccess.canAccess(#paymentId, authentication)")
    public Refund inspect(UUID paymentId, UUID refundId) {
        return store.read(paymentId, refundId);
    }

    @PreAuthorize("@paymentAccess.isAdministrator(authentication)")
    public Refund retry(UUID paymentId, UUID refundId) {
        checkTransaction();
        if (!Thread.currentThread().isInterrupted()) run(paymentId, refundId, true);
        return store.read(paymentId, refundId);
    }

    /** Internal worker entry point; no endpoint exposes a bulk financial retry. */
    public int retryDue() {
        checkTransaction();
        if (Thread.currentThread().isInterrupted()) return 0;
        int completed = 0;
        for (var candidate : store.dueRecovery().stream().limit(10).toList()) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                if (run(candidate.paymentId(), candidate.refundId(), false)) completed++;
            } catch (RuntimeException failure) {
                metrics.counter("payment.refund.recovery.failures").increment();
                LoggerFactory.getLogger(getClass())
                        .warn(
                                "Refund recovery pending: refund={} failure={}",
                                candidate.refundId(),
                                failure.getClass().getSimpleName());
            }
        }
        return completed;
    }

    private boolean run(UUID paymentId, UUID refundId, boolean force) {
        var attempt = store.claimRecovery(paymentId, refundId, provider.providerName(), force);
        if (attempt == null) return false;
        String action = attempt.lookupReference() == null ? "dispatch" : "lookup";
        metrics.counter("payment.refund.recovery.attempts", "action", action).increment();
        ProviderRefundResult result;
        try {
            result =
                    attempt.lookupReference() == null
                            ? provider.refund(attempt.request())
                            : provider.lookupRefund(attempt.request(), attempt.lookupReference());
            if (result == null || result.status() == null)
                throw new IllegalStateException("Provider refund response missing");
        } catch (RuntimeException failure) {
            result = ProviderRefundResult.error("Provider refund outcome unresolved");
        }
        metrics.counter("payment.refund.recovery.outcomes", "status", result.status().name())
                .increment();
        return store.complete(attempt, result).getStatus() != RefundStatus.PENDING;
    }

    private static void checkTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund recovery requires no ambient transaction");
    }
}

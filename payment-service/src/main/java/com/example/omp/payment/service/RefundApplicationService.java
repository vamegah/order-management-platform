package com.example.omp.payment.service;

import com.example.omp.payment.domain.Refund;
import com.example.omp.payment.provider.PaymentProvider;
import com.example.omp.payment.provider.ProviderRefundResult;
import com.example.omp.shared.domain.Money;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Both REST and gRPC execute the same durable provider-backed refund intent. */
@Service
public class RefundApplicationService {
    private final RefundStore store;
    private final PaymentProvider provider;

    public RefundApplicationService(RefundStore store, PaymentProvider provider) {
        this.store = store;
        this.provider = provider;
    }

    @org.springframework.security.access.prepost.PreAuthorize(
            "@paymentAccess.canRefund(#paymentId, authentication)")
    public Refund refund(UUID paymentId, long amountMinor, String reason, String key) {
        checkTransaction();
        return execute(
                store.prepare(paymentId, amountMinor, null, reason, key, provider.providerName()));
    }

    /** A missing amount means the remaining balance, bound once when the intent is created. */
    @org.springframework.security.access.prepost.PreAuthorize(
            "@paymentAccess.canRefund(#paymentId, authentication)")
    public Refund refund(UUID paymentId, Money amount, String reason, String key) {
        checkTransaction();
        return execute(
                store.prepare(paymentId, null, amount, reason, key, provider.providerName()));
    }

    private Refund execute(Refund refund) {
        var attempt = store.claim(refund.getPaymentId(), refund.getId());
        if (attempt == null) return store.read(refund.getPaymentId(), refund.getId());
        ProviderRefundResult result;
        try {
            result = provider.refund(attempt.request());
            if (result == null) throw new IllegalStateException("Missing provider refund response");
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass())
                    .warn(
                            "Refund provider outcome uncertain: refund={} failure={}",
                            refund.getId(),
                            failure.getClass().getSimpleName());
            result = ProviderRefundResult.error("Provider outcome uncertain");
        }
        return store.complete(attempt, result);
    }

    private static void checkTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund provider calls require no ambient transaction");
    }
}

package com.example.omp.payment.service;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.provider.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PaymentCancellationService {
    private final PaymentCancellationStore store;
    private final PaymentProvider provider;

    public PaymentCancellationService(PaymentCancellationStore store, PaymentProvider provider) {
        this.store = store;
        this.provider = provider;
    }

    public Payment cancel(UUID id) {
        return cancel(id, "Cancellation requested");
    }

    public Payment cancel(UUID id, String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Provider cancellation requires no ambient transaction");
        Payment payment = store.prepare(id, provider.providerName(), reason);
        if (payment.getStatus() == PaymentStatus.CANCELLED
                || payment.getStatus() == PaymentStatus.FAILED) return payment;
        if (payment.getStatus() != PaymentStatus.PENDING)
            throw new IllegalStateException("Captured payment requires a refund");
        if (payment.getProviderReference() == null)
            throw new IllegalStateException("Provider charge outcome is pending");
        var request =
                new ChargeRequest(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getCustomerId(),
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        "omp-cancel-" + id,
                        null,
                        payment.getPaymentMethodId(),
                        payment.getProviderReference());
        var result = provider.cancelCharge(request);
        payment = store.complete(id, result, reason);
        if (payment.getStatus() != PaymentStatus.CANCELLED
                && payment.getStatus() != PaymentStatus.FAILED)
            throw new IllegalStateException(
                    "Provider cancellation unresolved; reconcile payment before retrying cleanup");
        return payment;
    }
}

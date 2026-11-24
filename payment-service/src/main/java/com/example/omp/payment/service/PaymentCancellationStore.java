package com.example.omp.payment.service;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCancellationStore {
    private final PaymentRepository payments;
    private final PaymentEventPublisher events;

    public PaymentCancellationStore(PaymentRepository payments, PaymentEventPublisher events) {
        this.payments = payments;
        this.events = events;
    }

    private Payment lock(UUID id) {
        return payments.lockForRefund(id)
                .orElseThrow(
                        () ->
                                new com.example.omp.payment.error.PaymentNotFoundException(
                                        "Payment not found"));
    }

    @Transactional
    public Payment prepare(UUID id, String provider, String reason) {
        Payment payment = lock(id);
        if (payment.getStatus() != PaymentStatus.PENDING) return payment;
        if (payment.getProvider() != null && !provider.equals(payment.getProvider()))
            throw new IllegalStateException("Payment provider requires reconciliation");
        payment.setCancellationRequested(true);
        if ("noop".equals(provider)
                || (payment.getProviderReference() == null
                        && payment.getChargeStartedAt() == null
                        && payment.getPaymentMethodId() == null)) {
            payment.cancel("Cancelled before provider dispatch");
            events.publishCancelled(payment, reason);
        }
        return payment;
    }

    @Transactional
    public Payment complete(UUID id, ProviderChargeResult result, String reason) {
        Payment payment = lock(id);
        if (payment.getStatus() != PaymentStatus.PENDING) return payment;
        if (result.status() == ProviderChargeResult.Status.DECLINED
                && "provider_canceled".equals(result.declineCode())
                && payment.getProviderReference() != null
                && payment.getProviderReference().equals(result.providerReference())) {
            payment.cancel("Provider cancellation verified");
            events.publishCancelled(payment, reason);
        } else if (result.status() == ProviderChargeResult.Status.SUCCEEDED
                && payment.getProviderReference() != null
                && payment.getProviderReference().equals(result.providerReference())) {
            payment.markAuthorized(result.providerReference());
            payment.markCaptured();
            events.publishConfirmed(payment);
        }
        return payment;
    }
}

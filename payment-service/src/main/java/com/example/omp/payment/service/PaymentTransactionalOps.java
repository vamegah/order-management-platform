package com.example.omp.payment.service;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.PaymentStatus;
import com.example.omp.payment.outbox.OutboxWriter;
import com.example.omp.payment.outbox.PaymentEventPayloads;
import com.example.omp.payment.provider.ProviderChargeResult;
import com.example.omp.payment.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional operations for the payment flow.
 *
 * <p>Separated from PaymentApplicationService so that @Transactional proxies apply. The
 * orchestrator calls these through the injected bean reference.
 */
@Service
public class PaymentTransactionalOps {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactionalOps.class);

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final PaymentCreationLock creationLock;

    public PaymentTransactionalOps(
            PaymentRepository paymentRepository,
            OutboxWriter outboxWriter,
            PaymentCreationLock creationLock) {
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.creationLock = creationLock;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Payment createOrFindPending(
            PaymentApplicationService.ChargeCommand command, String providerName) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Payment idempotency key required");
        }
        if (command.amountMinor() <= 0)
            throw new IllegalArgumentException("Payment amount must be positive");
        if (command.orderId() == null
                || command.customerId() == null
                || providerName == null
                || providerName.isBlank()) {
            throw new IllegalArgumentException("Payment order, customer and provider are required");
        }
        var requestedAmount =
                com.example.omp.payment.domain.MinorUnits.toMoney(
                        command.amountMinor(),
                        command.currency() == null
                                ? ""
                                : command.currency().toUpperCase(java.util.Locale.ROOT));
        if (requestedAmount.getAmount().precision() > 19) {
            throw new IllegalArgumentException("Payment amount exceeds storage precision");
        }
        creationLock.acquire(command.idempotencyKey());
        Optional<Payment> existing =
                paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            Payment previous = existing.get();
            if (!previous.getOrderId().equals(command.orderId())
                    || !previous.getCustomerId().equals(command.customerId())
                    || previous.getAmountMinor() != command.amountMinor()
                    || !previous.getCurrency().equalsIgnoreCase(command.currency())) {
                throw new IllegalArgumentException(
                        "Idempotency key reused with different payment details");
            }
            if (previous.getProvider() != null && !previous.getProvider().equals(providerName)) {
                throw new IllegalArgumentException(
                        "Idempotency key reused with different provider");
            }
            if (previous.getProvider() == null) previous.setProvider(providerName);
            return previous;
        }

        Payment payment =
                new Payment(
                        command.orderId(),
                        command.customerId(),
                        requestedAmount,
                        command.idempotencyKey());
        payment.setProvider(providerName);

        Payment saved = paymentRepository.save(payment);

        outboxWriter.write(
                PaymentEventPayloads.PAYMENT_INITIATED,
                saved.getId(),
                PaymentEventPayloads.initiated(saved));

        return saved;
    }

    @Transactional
    public Payment applyChargeResult(UUID paymentId, ProviderChargeResult result) {
        Payment payment =
                paymentRepository
                        .findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Payment vanished mid-flight: " + paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return payment;
        }

        switch (result.status()) {
            case SUCCEEDED -> {
                payment.markAuthorized(result.providerReference());
                payment.markCaptured();
                paymentRepository.save(payment);
                outboxWriter.write(
                        PaymentEventPayloads.PAYMENT_CONFIRMED,
                        payment.getId(),
                        PaymentEventPayloads.confirmed(payment));
            }
            case DECLINED -> {
                payment.markFailed(result.declineCode(), result.declineMessage());
                paymentRepository.save(payment);
                outboxWriter.write(
                        PaymentEventPayloads.PAYMENT_FAILED,
                        payment.getId(),
                        PaymentEventPayloads.failed(payment));
            }
            case REQUIRES_ACTION -> {
                payment.setProviderReference(result.providerReference());
                paymentRepository.save(payment);
            }
            case ERROR -> {
                log.warn(
                        "Transient provider error for payment {}: {}",
                        paymentId,
                        result.declineMessage());
                // leave PENDING, no event
            }
        }
        return payment;
    }

    @Transactional
    public Payment markRefunded(
            UUID paymentId,
            UUID refundId,
            String providerReference,
            long amountMinor,
            String currency) {
        Payment payment =
                paymentRepository
                        .findById(paymentId)
                        .orElseThrow(
                                () -> new IllegalStateException("Payment not found: " + paymentId));
        payment.markRefunded();
        paymentRepository.save(payment);
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment findById(UUID paymentId) {
        return paymentRepository
                .findById(paymentId)
                .orElseThrow(
                        () -> new PaymentApplicationService.PaymentNotFoundException(paymentId));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByProviderReference(String provider, String reference) {
        return paymentRepository.findByProviderAndProviderReference(provider, reference);
    }
}

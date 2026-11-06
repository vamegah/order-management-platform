package com.example.omp.payment.grpc.handler;

import com.example.omp.payment.domain.MoneyConverter;
import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.error.DuplicateIdempotencyKeyException;
import com.example.omp.payment.error.PaymentNotFoundException;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.grpc.converter.ProtoConverters;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.proto.payment.CancelPaymentRequest;
import com.example.omp.proto.payment.ConfirmPaymentRequest;
import com.example.omp.proto.payment.CreatePaymentIntentRequest;
import com.example.omp.shared.domain.Money;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Handles intent creation, confirmation and cancellation with transactional idempotency. */
@Component
public class PaymentIntentHandler {
    @org.springframework.beans.factory.annotation.Value("${payment.provider:noop}")
    private String providerName = "noop";

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentHandler.class);

    private final PaymentRepository paymentRepository;
    private final MoneyConverter moneyConverter;
    private final PaymentEventPublisher paymentEventPublisher;
    private final com.example.omp.payment.service.PaymentCreationLock creationLock;

    public PaymentIntentHandler(
            PaymentRepository paymentRepository,
            MoneyConverter moneyConverter,
            PaymentEventPublisher paymentEventPublisher,
            com.example.omp.payment.service.PaymentCreationLock creationLock) {
        this.paymentRepository = paymentRepository;
        this.moneyConverter = moneyConverter;
        this.paymentEventPublisher = paymentEventPublisher;
        this.creationLock = creationLock;
    }

    // =========================================================================
    // Create
    // =========================================================================

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public com.example.omp.proto.payment.Payment create(CreatePaymentIntentRequest request) {
        String idempotencyKey = normalizeKey(request.getIdempotencyKey());
        UUID orderId = UUID.fromString(request.getOrderId());
        UUID customerId = UUID.fromString(request.getCustomerId());
        Money amount = moneyConverter.toDomain(request.getAmount());
        creationLock.acquire(idempotencyKey);
        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Payment previous = existing.get();
                if (!previous.getOrderId().equals(orderId)
                        || !previous.getCustomerId().equals(customerId)
                        || !previous.getAmount().equals(amount)) {
                    throw new DuplicateIdempotencyKeyException(
                            "Idempotency key reused with different payment details");
                }
                return ProtoConverters.toProto(previous, moneyConverter);
            }
        }

        // Build the entity and persist.
        Payment payment = new Payment(orderId, customerId, amount, idempotencyKey);
        payment.setProvider(providerName);

        paymentRepository.saveAndFlush(payment);
        paymentEventPublisher.publishIntentCreated(payment);

        log.info(
                "Created payment intent id={} order={} amount={}",
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount());

        return ProtoConverters.toProto(payment, moneyConverter);
    }

    // =========================================================================
    // Confirm
    // =========================================================================

    @Transactional
    public com.example.omp.proto.payment.Payment confirm(ConfirmPaymentRequest request) {
        Payment payment = findPayment(request.getPaymentId());

        // Idempotency: confirming twice with the same provider reference is
        // a no-op. This is essential because gRPC clients retry on transient
        // network failures.
        if (payment.getStatus() == com.example.omp.payment.domain.PaymentStatus.CONFIRMED
                && payment.getProviderReference() != null
                && payment.getProviderReference().equals(request.getProviderReference())) {
            log.debug(
                    "Confirm is idempotent — payment {} already confirmed with same provider ref",
                    payment.getId());
            return ProtoConverters.toProto(payment, moneyConverter);
        }

        // Domain method validates the transition (must be PENDING).
        payment.confirm(request.getProviderReference());
        paymentEventPublisher.publishConfirmed(payment);

        log.info(
                "Confirmed payment id={} providerRef={}",
                payment.getId(),
                request.getProviderReference());

        return ProtoConverters.toProto(payment, moneyConverter);
    }

    // =========================================================================
    // Cancel
    // =========================================================================

    @Transactional
    public com.example.omp.proto.payment.Payment cancel(CancelPaymentRequest request) {
        Payment payment = findPayment(request.getPaymentId());

        // Idempotency: cancel is a no-op if already cancelled.
        if (payment.getStatus() == com.example.omp.payment.domain.PaymentStatus.CANCELLED) {
            log.debug("Cancel is idempotent — payment {} already cancelled", payment.getId());
            return ProtoConverters.toProto(payment, moneyConverter);
        }

        payment.cancel(request.getReason());
        paymentEventPublisher.publishCancelled(payment, request.getReason());

        log.info("Cancelled payment id={}", payment.getId());
        return ProtoConverters.toProto(payment, moneyConverter);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Payment findPayment(String idString) {
        UUID id;
        try {
            id = UUID.fromString(idString);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid payment ID: " + idString, e);
        }
        return paymentRepository
                .findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + id));
    }

    /** Empty strings normalize to null so the unique index treats them consistently. */
    private String normalizeKey(String key) {
        return (key == null || key.isBlank()) ? null : key;
    }
}

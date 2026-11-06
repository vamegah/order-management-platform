package com.example.omp.payment.service;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.PaymentProviderEvent;
import com.example.omp.payment.domain.PaymentStatus;
import com.example.omp.payment.outbox.OutboxWriter;
import com.example.omp.payment.outbox.PaymentEventPayloads;
import com.example.omp.payment.provider.ProviderWebhookEvent;
import com.example.omp.payment.repository.PaymentProviderEventRepository;
import com.example.omp.payment.repository.PaymentRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles inbound PSP events.
 *
 * <p>Idempotency: (provider, provider_event_id) is unique. A duplicate insert is caught and treated
 * as DUPLICATE.
 *
 * <p>Payment intent events confirm or fail pending payments. Correlated refund events complete or
 * fail durable refund intents. Aggregate charge refund echoes are audited without reapplying money.
 */
@Service
public class PaymentProviderEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderEventService.class);

    private final PaymentProviderEventRepository eventRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final RefundStore refunds;

    public PaymentProviderEventService(
            PaymentProviderEventRepository eventRepository,
            PaymentRepository paymentRepository,
            OutboxWriter outboxWriter,
            RefundStore refunds) {
        this.eventRepository = eventRepository;
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.refunds = refunds;
    }

    public enum ProcessResult {
        ACCEPTED,
        DUPLICATE,
        IGNORED
    }

    @Transactional
    public ProcessResult process(String providerName, ProviderWebhookEvent event) {
        // 1. Idempotency: attempt insert.
        java.util.UUID recordId = java.util.UUID.randomUUID();
        int inserted =
                eventRepository.insertIfAbsent(
                        recordId,
                        providerName,
                        event.eventId(),
                        event.eventType(),
                        event.data().toString());
        if (inserted == 0) return ProcessResult.DUPLICATE;
        PaymentProviderEvent record = eventRepository.findById(recordId).orElseThrow();
        // 2. Locate the Payment.
        Payment payment = locatePayment(providerName, event).orElse(null);
        if (payment == null) {
            log.warn(
                    "PSP event {} references unknown payment ref {}",
                    event.eventId(),
                    event.paymentRef());
            record.markIgnored();
            eventRepository.save(record);
            return ProcessResult.IGNORED;
        }

        // 3. Apply the event.
        try {
            boolean changed =
                    java.util.Set.of("refund.created", "refund.updated", "refund.failed")
                                    .contains(event.eventType())
                            ? refunds.webhook(payment.getId(), providerName, event)
                            : applyEvent(payment, event);
            record.markProcessed();
            eventRepository.save(record);
            return changed ? ProcessResult.ACCEPTED : ProcessResult.IGNORED;
        } catch (RuntimeException e) {
            record.markFailed(e.getMessage());
            eventRepository.save(record);
            throw e;
        }
    }

    private Optional<Payment> locatePayment(String providerName, ProviderWebhookEvent event) {
        if (event.paymentRef() == null) {
            return Optional.empty();
        }
        return paymentRepository.lockByProviderReference(providerName, event.paymentRef());
    }

    private boolean applyEvent(Payment payment, ProviderWebhookEvent event) {
        String type = event.eventType();

        switch (type) {
            case "payment_intent.succeeded" -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.markAuthorized(payment.getProviderReference());
                    payment.markCaptured();
                    paymentRepository.save(payment);
                    outboxWriter.write(
                            PaymentEventPayloads.PAYMENT_CONFIRMED,
                            payment.getId(),
                            PaymentEventPayloads.confirmed(payment));
                    return true;
                }
                return false;
            }
            case "payment_intent.payment_failed", "payment_intent.canceled" -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    String code =
                            event.data()
                                    .path("last_payment_error")
                                    .path("decline_code")
                                    .asText("psp_declined");
                    String msg =
                            event.data()
                                    .path("last_payment_error")
                                    .path("message")
                                    .asText("PSP reported failure");
                    payment.markFailed(code, msg);
                    paymentRepository.save(payment);
                    outboxWriter.write(
                            PaymentEventPayloads.PAYMENT_FAILED,
                            payment.getId(),
                            PaymentEventPayloads.failed(payment));
                    return true;
                }
                return false;
            }
            case "charge.refunded" -> {
                // Aggregate charge notifications cannot identify an individual refund.
                // Applying them in addition to refund.* would count a refund twice.
                return false;
            }
            default -> {
                log.debug("Unhandled PSP event type {} for payment {}", type, payment.getId());
                return false;
            }
        }
    }
}

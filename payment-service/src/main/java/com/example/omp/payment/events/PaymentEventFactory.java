package com.example.omp.payment.events;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.Refund;
import com.example.omp.shared.events.PaymentEventType;
import com.example.omp.shared.events.PaymentEvents;
import java.time.Instant;
import java.util.UUID;

public final class PaymentEventFactory {

    private PaymentEventFactory() {}

    public static PaymentEvents.PaymentIntentCreated intentCreated(Payment payment) {
        return new PaymentEvents.PaymentIntentCreated(
                UUID.randomUUID().toString(),
                PaymentEventType.PAYMENT_INTENT_CREATED,
                Instant.now(),
                PaymentEvents.PaymentIntentCreated.CURRENT_SCHEMA_VERSION,
                payment.getId().toString(),
                payment.getOrderId().toString(),
                payment.getCustomerId().toString(),
                payment.getStatus().name(),
                payment.getAmount().getAmount().toPlainString(),
                payment.getAmount().getCurrencyCode());
    }

    public static PaymentEvents.PaymentConfirmed confirmed(Payment payment) {
        return new PaymentEvents.PaymentConfirmed(
                UUID.randomUUID().toString(),
                PaymentEventType.PAYMENT_CONFIRMED,
                Instant.now(),
                PaymentEvents.PaymentConfirmed.CURRENT_SCHEMA_VERSION,
                payment.getId().toString(),
                payment.getOrderId().toString(),
                payment.getCustomerId().toString(),
                payment.getStatus().name(),
                payment.getProviderReference());
    }

    public static PaymentEvents.PaymentCancelled cancelled(Payment payment, String reason) {
        return new PaymentEvents.PaymentCancelled(
                UUID.randomUUID().toString(),
                PaymentEventType.PAYMENT_CANCELLED,
                Instant.now(),
                PaymentEvents.PaymentCancelled.CURRENT_SCHEMA_VERSION,
                payment.getId().toString(),
                payment.getOrderId().toString(),
                payment.getCustomerId().toString(),
                payment.getStatus().name(),
                reason);
    }

    public static PaymentEvents.PaymentFailed failed(Payment payment, String reason) {
        return new PaymentEvents.PaymentFailed(
                UUID.randomUUID().toString(),
                PaymentEventType.PAYMENT_FAILED,
                Instant.now(),
                PaymentEvents.PaymentFailed.CURRENT_SCHEMA_VERSION,
                payment.getId().toString(),
                payment.getOrderId().toString(),
                payment.getCustomerId().toString(),
                payment.getStatus().name(),
                reason);
    }

    public static PaymentEvents.PaymentRefunded refunded(Payment payment, Refund refund) {
        return new PaymentEvents.PaymentRefunded(
                UUID.randomUUID().toString(),
                PaymentEventType.PAYMENT_REFUNDED,
                Instant.now(),
                PaymentEvents.PaymentRefunded.CURRENT_SCHEMA_VERSION,
                payment.getId().toString(),
                payment.getOrderId().toString(),
                payment.getCustomerId().toString(),
                payment.getStatus().name(),
                refund.getAmount().getAmount().toPlainString(),
                refund.getAmount().getCurrencyCode(),
                refund.getReason());
    }

    public static PaymentEvents.PaymentPartiallyRefunded partiallyRefunded(
            Payment payment, Refund refund) {
        return new PaymentEvents.PaymentPartiallyRefunded(
                UUID.randomUUID().toString(),
                PaymentEventType.PAYMENT_PARTIALLY_REFUNDED,
                Instant.now(),
                PaymentEvents.PaymentPartiallyRefunded.CURRENT_SCHEMA_VERSION,
                payment.getId().toString(),
                payment.getOrderId().toString(),
                payment.getCustomerId().toString(),
                payment.getStatus().name(),
                refund.getAmount().getAmount().toPlainString(),
                payment.getRefundedAmount().getAmount().toPlainString(),
                refund.getAmount().getCurrencyCode(),
                refund.getReason());
    }
}

package com.example.omp.payment.outbox;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.Refund;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payload shapes for payment domain events.
 *
 * <p>These are the "data" field inside the standard outbox envelope. The envelope itself is added
 * by OutboxWriter.
 *
 * <p>Keep these stable: notification-service's webhook matcher and WebSocket subscribers both
 * depend on the field names.
 */
public final class PaymentEventPayloads {

    private PaymentEventPayloads() {}

    public static final String PAYMENT_INITIATED = "payment.initiated";
    public static final String PAYMENT_CONFIRMED = "payment.confirmed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";

    public static Map<String, Object> initiated(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.getId().toString());
        m.put("orderId", p.getOrderId().toString());
        m.put("customerId", p.getCustomerId().toString());
        m.put("amountMinor", p.getAmountMinor());
        m.put("currency", p.getCurrency());
        m.put("status", p.getStatus().name());
        m.put("provider", p.getProvider());
        m.put("initiatedAt", p.getCreatedAt().toString());
        return m;
    }

    public static Map<String, Object> confirmed(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.getId().toString());
        m.put("orderId", p.getOrderId().toString());
        m.put("customerId", p.getCustomerId().toString());
        m.put("amountMinor", p.getAmountMinor());
        m.put("currency", p.getCurrency());
        m.put("status", p.getStatus().name());
        m.put("provider", p.getProvider());
        m.put("providerReference", p.getProviderReference());
        m.put(
                "capturedAt",
                p.getCapturedAt() != null
                        ? p.getCapturedAt().toString()
                        : Instant.now().toString());
        return m;
    }

    public static Map<String, Object> failed(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.getId().toString());
        m.put("orderId", p.getOrderId().toString());
        m.put("customerId", p.getCustomerId().toString());
        m.put("amountMinor", p.getAmountMinor());
        m.put("currency", p.getCurrency());
        m.put("status", p.getStatus().name());
        m.put("provider", p.getProvider());
        m.put("failureCode", p.getFailureCode());
        m.put("failureMessage", p.getFailureMessage());
        m.put(
                "failedAt",
                p.getFailedAt() != null ? p.getFailedAt().toString() : Instant.now().toString());
        return m;
    }

    public static Map<String, Object> refunded(Payment p, Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.getId().toString());
        m.put("refundId", r.getId().toString());
        m.put("orderId", p.getOrderId().toString());
        m.put("customerId", p.getCustomerId().toString());
        m.put("amountMinor", r.getAmountMinor());
        m.put("currency", r.getCurrency());
        m.put("status", p.getStatus().name());
        m.put("provider", p.getProvider());
        m.put("providerReference", r.getProviderReference());
        m.put(
                "refundedAt",
                r.getCompletedAt() != null
                        ? r.getCompletedAt().toString()
                        : Instant.now().toString());
        return m;
    }

    /** Utility for tests: stable UUID for a synthetic aggregate. */
    public static UUID syntheticId() {
        return UUID.randomUUID();
    }
}

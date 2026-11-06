package com.example.omp.shared.events;

import java.time.Instant;

/**
 * Concrete payment event records.
 *
 * <p>Published to the {@code payments.events.payment} topic. Consumed by notification-service to
 * push payment status updates to customers in real time.
 *
 * <p>Amounts are serialized as strings (like the gRPC Money message) to preserve decimal precision.
 * Consumers parse to BigDecimal.
 *
 * <p><b>Envelope fields</b> (eventId, eventType, occurredAt, schemaVersion, paymentId, orderId,
 * customerId, status) appear on every record. Domain-specific fields follow.
 */
public final class PaymentEvents {

    private PaymentEvents() {
        // Container class — only nested records.
    }

    /** Emitted when a payment intent is created (status PENDING). */
    public record PaymentIntentCreated(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String paymentId,
            String orderId,
            String customerId,
            String status,
            String amount,
            String currency)
            implements PaymentEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a payment is confirmed (PENDING → CONFIRMED). */
    public record PaymentConfirmed(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String paymentId,
            String orderId,
            String customerId,
            String status,
            String providerReference)
            implements PaymentEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a payment is cancelled. */
    public record PaymentCancelled(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String paymentId,
            String orderId,
            String customerId,
            String status,
            String reason)
            implements PaymentEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a payment fails. */
    public record PaymentFailed(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String paymentId,
            String orderId,
            String customerId,
            String status,
            String reason)
            implements PaymentEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /** Emitted when a payment is fully refunded. */
    public record PaymentRefunded(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String paymentId,
            String orderId,
            String customerId,
            String status,
            String refundedAmount,
            String currency,
            String reason)
            implements PaymentEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }

    /**
     * Emitted when a payment is partially refunded.
     *
     * <p>Contains both the amount of the current refund and the total cumulative refunded amount
     * (across all refunds on this payment).
     */
    public record PaymentPartiallyRefunded(
            String eventId,
            String eventType,
            Instant occurredAt,
            int schemaVersion,
            String paymentId,
            String orderId,
            String customerId,
            String status,
            String refundedAmount,
            String totalRefunded,
            String currency,
            String reason)
            implements PaymentEvent {
        public static final int CURRENT_SCHEMA_VERSION = 1;
    }
}

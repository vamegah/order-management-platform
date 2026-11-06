package com.example.omp.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The JSON body sent by the simulator in its webhook POSTs.
 *
 * <p>Shaped to resemble Stripe's webhook payload structure. A real Stripe event has more fields;
 * this is the subset relevant to our payment flow.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code id} — unique webhook event ID (this is what payment-service uses for idempotency;
 *       equivalent to Stripe's {@code evt_...} ID).
 *   <li>{@code type} — event type, e.g., {@code payment_intent.succeeded}. Matches Stripe's event
 *       type naming convention.
 *   <li>{@code created} — Unix timestamp (seconds) when the event occurred.
 *   <li>{@code paymentIntentId} — the ID of the payment intent that changed state (equivalent to
 *       Stripe's {@code pi_...} ID).
 *   <li>{@code amount} — payment amount, for verification against our recorded amount.
 *   <li>{@code currency} — ISO 4217 currency code.
 *   <li>{@code status} — provider-side status, e.g., {@code succeeded}.
 *   <li>{@code failureReason} — populated when {@code type} indicates a failure (e.g., {@code
 *       payment_intent.payment_failed}).
 * </ul>
 */
public record WebhookEventPayload(
        String id,
        String type,
        long created,
        String paymentIntentId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason) {

    /**
     * Convenience: the event's creation time as an {@link Instant}. Not serialized (records
     * serialize only their components), but available to Java callers.
     */
    public Instant createdAt() {
        return Instant.ofEpochSecond(created);
    }

    // -------------------------------------------------------------------------
    // Factory methods for the two event types the simulator produces
    // -------------------------------------------------------------------------

    /** Builds a {@code payment_intent.succeeded} event. */
    public static WebhookEventPayload succeeded(
            String eventId, String paymentIntentId, BigDecimal amount, String currency) {
        return new WebhookEventPayload(
                eventId,
                "payment_intent.succeeded",
                Instant.now().getEpochSecond(),
                paymentIntentId,
                amount,
                currency,
                "succeeded",
                null);
    }

    /** Builds a {@code payment_intent.payment_failed} event. */
    public static WebhookEventPayload failed(
            String eventId,
            String paymentIntentId,
            BigDecimal amount,
            String currency,
            String reason) {
        return new WebhookEventPayload(
                eventId,
                "payment_intent.payment_failed",
                Instant.now().getEpochSecond(),
                paymentIntentId,
                amount,
                currency,
                "failed",
                reason);
    }
}

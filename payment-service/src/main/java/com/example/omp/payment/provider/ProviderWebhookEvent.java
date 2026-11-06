package com.example.omp.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A verified, parsed PSP webhook event.
 *
 * <p>eventId: provider's unique event id (used for idempotency) eventType: provider's event type
 * string (e.g. "payment_intent.succeeded") data: the full event JSON (vendor-shaped) paymentRef:
 * the provider's reference for the payment this event concerns, extracted by the provider
 * implementation when possible
 */
public record ProviderWebhookEvent(
        String eventId, String eventType, JsonNode data, String paymentRef) {}

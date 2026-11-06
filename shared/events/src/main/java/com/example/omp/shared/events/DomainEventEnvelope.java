package com.example.omp.shared.events;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shared view of the domain event envelope produced by source services' outbox writers (Batch 9.1)
 * and consumed by notification-service.
 *
 * <p>Envelope shape (see OutboxWriter in each source service): { "eventId": "...", "eventType":
 * "payment.confirmed", "aggregateId": "...", "occurredAt": "2026-...", "schemaVersion": 1, "data":
 * { ... } }
 *
 * <p>This is the single parser used by both notification consumers (WebSocket push, Phase 4;
 * outbound webhook fan-out, Batch 9.3). Keeping parsing in one place avoids drift between the two
 * paths.
 */
public record DomainEventEnvelope(
        String eventId,
        String eventType,
        String aggregateId,
        String occurredAt,
        int schemaVersion,
        JsonNode data) {

    public boolean isValid() {
        return eventId != null && !eventId.isBlank() && eventType != null && !eventType.isBlank();
    }
}

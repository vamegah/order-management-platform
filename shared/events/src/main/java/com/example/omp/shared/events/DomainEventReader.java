package com.example.omp.shared.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Parses raw Kafka payloads into DomainEventEnvelope.
 *
 * <p>Tolerant to two shapes: 1. The outbox envelope (as produced by Batch 9.1). 2. A bare event
 * body (used by early prototypes and tests) — in that case, eventId/eventType are read from
 * top-level fields.
 */
@Component
public class DomainEventReader {

    private final ObjectMapper objectMapper;

    public DomainEventReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DomainEventEnvelope read(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject())
                throw new IllegalArgumentException("Expected an event object");
            JsonNode data = node.has("data") ? node.get("data") : node;
            if (!data.isObject()) throw new IllegalArgumentException("Expected object event data");
            String eventId = text(node, "eventId", "id");
            String eventType = text(node, "eventType", "type");
            if (eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("Missing event identity");
            }
            int version = 1;
            if (node.has("schemaVersion")) {
                var schema = node.get("schemaVersion");
                if (!schema.isIntegralNumber()
                        || !schema.canConvertToInt()
                        || schema.intValue() < 1) {
                    throw new IllegalArgumentException("Invalid event schema version");
                }
                version = schema.intValue();
            }
            String aggregateId = text(node, "aggregateId");
            if (aggregateId == null) {
                aggregateId =
                        switch (new EventRegistry().categoryOf(eventType)) {
                            case ORDER -> text(data, "orderId");
                            case INVENTORY -> text(data, "reservationId");
                            case PAYMENT -> text(data, "paymentId");
                            case UNKNOWN -> null;
                        };
            }

            return new DomainEventEnvelope(
                    eventId,
                    eventType,
                    aggregateId,
                    text(node, "occurredAt", "timestamp"),
                    version,
                    data);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable domain event payload", e);
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                if (!v.isTextual())
                    throw new IllegalArgumentException("Expected text event metadata");
                return v.textValue();
            }
        }
        return null;
    }
}

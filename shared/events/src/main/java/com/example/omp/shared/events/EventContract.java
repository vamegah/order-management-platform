package com.example.omp.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Version-one wire boundary. Unknown fields are additive; unknown versions/types are quarantined.
 */
public final class EventContract {
    private static final ObjectMapper MAPPER = EventSerde.buildObjectMapper();
    private static final EventRegistry REGISTRY = new EventRegistry();
    private static final Map<String, String> ALIASES =
            Map.ofEntries(
                    Map.entry("order.placed", "ORDER_PLACED"),
                    Map.entry("order.shipped", "ORDER_SHIPPED"),
                    Map.entry("order.cancelled", "ORDER_CANCELLED"),
                    Map.entry("inventory.reserved", "RESERVATION_CREATED"),
                    Map.entry("inventory.released", "RESERVATION_RELEASED"),
                    Map.entry("payment.initiated", "PAYMENT_INTENT_CREATED"),
                    Map.entry("payment.confirmed", "PAYMENT_CONFIRMED"),
                    Map.entry("payment.failed", "PAYMENT_FAILED"),
                    Map.entry("payment.refunded", "PAYMENT_REFUNDED"));

    private EventContract() {}

    public static DomainEventEnvelope validate(String payload, String topic) {
        if (payload == null || payload.length() > 262144) throw new InvalidEventException();
        try {
            DomainEventEnvelope e = new DomainEventReader(MAPPER).read(payload);
            canonicalUuid(e.eventId());
            canonicalUuid(e.aggregateId());
            Instant.parse(e.occurredAt());
            if (e.schemaVersion() != 1 || !REGISTRY.knows(e.eventType()))
                throw new InvalidEventException();
            String expected =
                    switch (REGISTRY.categoryOf(e.eventType())) {
                        case ORDER -> KafkaTopics.ORDERS_EVENTS_ORDER;
                        case PAYMENT -> KafkaTopics.PAYMENTS_EVENTS_PAYMENT;
                        case INVENTORY -> KafkaTopics.INVENTORY_EVENTS_RESERVATION;
                        default -> throw new InvalidEventException();
                    };
            if (!expected.equals(topic)) throw new InvalidEventException();
            String field =
                    switch (REGISTRY.categoryOf(e.eventType())) {
                        case ORDER -> "orderId";
                        case PAYMENT -> "paymentId";
                        default -> "reservationId";
                    };
            if (!e.aggregateId().equals(e.data().path(field).asText()))
                throw new InvalidEventException();
            if (REGISTRY.categoryOf(e.eventType()) != EventRegistry.Category.INVENTORY) {
                canonicalUuid(e.data().path("customerId").asText());
            } else if (e.data().path("sku").asText().isBlank()) throw new InvalidEventException();
            var root = MAPPER.readTree(payload);
            if (root.has("data")) {
                for (String mirrored :
                        java.util.List.of(
                                "eventId",
                                "eventType",
                                "occurredAt",
                                "schemaVersion",
                                "orderId",
                                "paymentId",
                                "reservationId",
                                "customerId",
                                "warehouseId",
                                "sku")) {
                    if (root.has(mirrored)
                            && e.data().has(mirrored)
                            && !root.get(mirrored).equals(e.data().get(mirrored)))
                        throw new InvalidEventException();
                }
            }
            for (String identity :
                    java.util.List.of("orderId", "paymentId", "reservationId", "customerId")) {
                if (e.data().has(identity)) canonicalUuid(e.data().get(identity).asText());
            }
            for (String text : java.util.List.of("status", "orderNumber", "amount", "currency")) {
                if (e.data().has(text) && !e.data().get(text).isTextual())
                    throw new InvalidEventException();
            }
            if (e.data().has("warehouseId")
                    && (!e.data().get("warehouseId").isTextual()
                            || e.data().get("warehouseId").asText().isBlank()
                            || e.data().get("warehouseId").asText().length() > 255))
                throw new InvalidEventException();
            if (e.data().has("status") && !e.data().get("status").asText().matches("[A-Z][A-Z_]*"))
                throw new InvalidEventException();
            if (e.data().has("amountMinor") && !e.data().get("amountMinor").isIntegralNumber())
                throw new InvalidEventException();
            if (REGISTRY.categoryOf(e.eventType()) == EventRegistry.Category.INVENTORY) {
                if (!e.data().path("sku").isTextual()) throw new InvalidEventException();
                var quantity = e.data().get("quantity");
                if (quantity != null
                        && (!quantity.isIntegralNumber()
                                || !quantity.canConvertToInt()
                                || quantity.intValue() < 1)) throw new InvalidEventException();
            }
            return e;
        } catch (InvalidEventException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidEventException();
        }
    }

    public static BaseEvent typed(DomainEventEnvelope envelope) {
        try {
            ObjectNode data = ((ObjectNode) envelope.data()).deepCopy();
            String type = ALIASES.getOrDefault(envelope.eventType(), envelope.eventType());
            data.put("eventId", envelope.eventId());
            data.put("eventType", type);
            data.put("occurredAt", envelope.occurredAt());
            data.put("schemaVersion", envelope.schemaVersion());
            return MAPPER.treeToValue(data, REGISTRY.eventClass(type));
        } catch (Exception e) {
            throw new InvalidEventException();
        }
    }

    private static void canonicalUuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new InvalidEventException();
    }

    public static class InvalidEventException extends IllegalArgumentException {
        public InvalidEventException() {
            super("Event does not satisfy the supported contract");
        }
    }
}

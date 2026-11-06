package com.example.omp.payment.outbox;

import com.example.omp.shared.events.KafkaTopics;
import com.example.omp.shared.events.outbox.OutboxEvent;
import com.example.omp.shared.events.outbox.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes domain events to the transactional outbox table (Batch 9.1).
 *
 * <p>The outbox table is owned by the shared schema; the writer inserts one row per event. A
 * separate relay (Batch 9.1) publishes rows to Kafka in order.
 *
 * <p>Important: this is the ONLY path by which payment-service emits events. Business state changes
 * and outbox writes happen in the same transaction, so no event is lost and no event is emitted for
 * a rolled-back change.
 */
@Component("paymentOutboxWriter")
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param eventType the domain event type (e.g. "payment.confirmed")
     * @param aggregateId the payment id (used as Kafka partition key downstream)
     * @param payload the event body; serialized to JSON
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UUID write(String eventType, UUID aggregateId, Object payload) {
        try {
            OutboxEvent entry =
                    new OutboxEvent(
                            aggregateId.toString(),
                            "PAYMENT",
                            eventType,
                            KafkaTopics.PAYMENTS_EVENTS_PAYMENT,
                            objectMapper.writeValueAsString(wrap(eventType, aggregateId, payload)));
            return outboxRepository.save(entry).getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for " + eventType, e);
        }
    }

    /**
     * Standard envelope shared by all domain events. The same shape is consumed by
     * notification-service (WebSocket + webhook).
     */
    private Map<String, Object> wrap(String eventType, UUID aggregateId, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("schemaVersion", 1);
        envelope.put("data", payload);
        return envelope;
    }
}

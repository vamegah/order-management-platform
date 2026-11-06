package com.example.omp.shared.events.outbox;

import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.EventSerde;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The API services use to record an event in the outbox.
 *
 * <p>Callers invoke {@link #write(BaseEvent, String)} <em>inside</em> the same transaction that
 * performs the state change. The outbox row and the state change commit together or not at all —
 * this is the transactional guarantee.
 *
 * <p>The actual Kafka publish is deferred to {@link OutboxDrainer}, which runs on a separate thread
 * and periodically polls the outbox table.
 *
 * <p><b>Typical usage:</b>
 *
 * <pre>{@code
 * @Transactional
 * public Order save(Order order) {
 *     Order saved = repository.save(order);
 *     outboxWriter.write(OrderEventFactory.placed(saved, "alice"),
 *                        KafkaTopics.ORDERS_EVENTS_ORDER);
 *     return saved;
 * }
 * }</pre>
 *
 * <p>The event is serialized to JSON at write time so that the payload is frozen as it was when the
 * transaction committed. Later changes to the event class (adding a field, for example) don't
 * affect the already-stored payload.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository) {
        this.repository = repository;
        this.objectMapper = EventSerde.buildObjectMapper();
    }

    /**
     * Records an event in the outbox. Must be called within a transaction that also writes the
     * state change that produced the event.
     *
     * @param event the domain event (any {@link BaseEvent} subtype)
     * @param topic the destination Kafka topic
     * @throws OutboxException if the event cannot be serialized
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent write(BaseEvent event, String topic) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "Outbox writes require an active read-write transaction");
        }
        java.util.Objects.requireNonNull(event, "event");
        if (event.eventId() == null
                || event.eventId().isBlank()
                || event.occurredAt() == null
                || event.schemaVersion() < 1) {
            throw new IllegalArgumentException(
                    "An outbox event requires an ID, timestamp and positive schema version");
        }
        String payload = serialize(event);

        OutboxEvent row =
                new OutboxEvent(
                        event.aggregateId(),
                        aggregateType(event),
                        event.eventType(),
                        topic,
                        payload);

        OutboxEvent saved = repository.save(row);

        log.debug(
                "Outbox write: id={} eventType={} topic={} aggregateId={}",
                saved.getId(),
                event.eventType(),
                topic,
                event.aggregateId());

        return saved;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private String serialize(BaseEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new OutboxException("Failed to serialize event " + event.eventType(), e);
        }
    }

    /**
     * Derives the aggregate type from the event class.
     *
     * <p>Used for filtering and reporting. We infer it from the concrete event type name; the
     * caller doesn't have to pass it.
     */
    private String aggregateType(BaseEvent event) {
        String type = event.eventType();
        if (type.startsWith("ORDER_")) {
            return "ORDER";
        }
        if (type.startsWith("RESERVATION_")) {
            return "RESERVATION";
        }
        if (type.startsWith("PAYMENT_")) {
            return "PAYMENT";
        }
        return "UNKNOWN";
    }
}

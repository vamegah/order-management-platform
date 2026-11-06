package com.example.omp.shared.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Common envelope for every event published to Kafka.
 *
 * <p>Every domain event implements this interface. The interface provides the fields every event
 * needs regardless of its domain:
 *
 * <ul>
 *   <li>{@code eventId} — unique identifier for this event (UUID). Used for idempotency and
 *       tracing. Consumers should deduplicate on this field if they process events more than once
 *       (Kafka's at-least-once delivery).
 *   <li>{@code eventType} — the specific event type as a string (e.g., "ORDER_CREATED"). Lets
 *       consumers filter and dispatch without deserializing the full payload to the concrete type.
 *   <li>{@code occurredAt} — when the event actually happened (domain time), not when it was
 *       produced or consumed. This is different from Kafka's broker-assigned timestamp, which is
 *       when the broker received the message.
 *   <li>{@code aggregateId} — the ID of the aggregate this event concerns (e.g., the order ID, the
 *       reservation ID, the payment ID). Kafka uses this as the partition key, ensuring all events
 *       for the same aggregate land on the same partition and are consumed in order.
 *   <li>{@code schemaVersion} — the version of the event's schema. Starts at 1. Increments only on
 *       breaking changes (removing a field, changing a field's type). Additive changes don't
 *       increment this.
 * </ul>
 *
 * <p><b>Why an interface, not a base class?</b> Java records can't extend classes. An interface
 * lets each event be a record while still sharing the common envelope shape. Consumers that don't
 * care about the payload can work with {@code BaseEvent} and access these fields without knowing
 * the concrete type.
 *
 * <p><b>Serialization:</b> Jackson serializes records as JSON objects with one property per
 * component. Deserialization requires the concrete type (e.g., {@code OrderCreatedEvent.class}),
 * not {@code BaseEvent.class}. See {@link EventSerde} for how this is wired.
 */
public interface BaseEvent {

    /**
     * Unique identifier for this event instance. Format: UUID string. Used for idempotency —
     * consumers may deduplicate on this.
     */
    String eventId();

    /**
     * The specific event type. Must match one of the constants in the domain's event type enum
     * (e.g., {@link OrderEventType}).
     */
    String eventType();

    /**
     * Domain time — when the event occurred, not when Kafka received it. Usually set by the
     * producer at the moment of the state change.
     */
    Instant occurredAt();

    /**
     * The ID of the aggregate this event concerns. Kafka uses this as the partition key. Guarantees
     * ordering per aggregate.
     */
    @JsonProperty("aggregateId")
    String aggregateId();

    /** Schema version of this event. Starts at 1. Increments on breaking changes only. */
    int schemaVersion();
}

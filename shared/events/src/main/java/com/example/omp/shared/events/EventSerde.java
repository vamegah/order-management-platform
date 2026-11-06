package com.example.omp.shared.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * JSON Serde (serializer/deserializer) for platform events.
 *
 * <p>Events are serialized as JSON strings. Serialization is configured:
 *
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} — deserialization ignores fields the consumer
 *       doesn't know about. This lets producers add fields without breaking old consumers.
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS=false} — {@link java.time.Instant} serializes as ISO 8601
 *       strings, not epoch numbers. Human-readable in Kafka UI and console tools.
 *   <li>{@code NON_NULL} — null fields are omitted, keeping payloads compact.
 *   <li>{@code JavaTimeModule} — enables {@code Instant} support.
 * </ul>
 *
 * <p><b>Deserialization type:</b> the event type selects a concrete record from a fixed registry.
 * Unknown types, breaking schema versions and malformed envelopes raise Kafka serialization errors.
 * Tombstones are represented by null in both directions.
 *
 * <p>The deserializer dispatches canonical event types through {@link EventRegistry}, without
 * accepting class names or enabling Jackson default typing. Dotted envelope aliases are read by
 * {@link DomainEventReader} instead.
 */
public final class EventSerde implements Serde<BaseEvent> {

    private final ObjectMapper mapper;

    public EventSerde() {
        this.mapper = buildObjectMapper();
    }

    @Override
    public Serializer<BaseEvent> serializer() {
        return (topic, event) -> {
            if (event == null) {
                return null;
            }
            try {
                validate(event);
                return mapper.writeValueAsBytes(event);
            } catch (Exception e) {
                throw new SerializationException(
                        "Failed to serialize event of type " + event.getClass().getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<BaseEvent> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) return null; // Kafka tombstone.
            try {
                var node = mapper.readTree(bytes);
                if (node == null || !node.isObject() || !node.path("eventType").isTextual()) {
                    throw new SerializationException(
                            "Expected a domain event object with eventType");
                }
                var type = new EventRegistry().eventClass(node.get("eventType").textValue());
                if (type == null) throw new SerializationException("Unsupported domain event type");
                BaseEvent event = mapper.treeToValue(node, type);
                validate(event);
                return event;
            } catch (SerializationException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SerializationException("Invalid domain event payload", exception);
            }
        };
    }

    private static void validate(BaseEvent event) {
        if (event.eventId() == null
                || event.eventId().isBlank()
                || event.eventType() == null
                || event.eventType().isBlank()
                || event.aggregateId() == null
                || event.aggregateId().isBlank()
                || event.occurredAt() == null
                || event.schemaVersion() != 1) {
            throw new SerializationException("Invalid or unsupported domain event envelope");
        }
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No additional config.
    }

    /** Returns the configured ObjectMapper for direct use. */
    public ObjectMapper objectMapper() {
        return mapper;
    }

    /**
     * Static helper: creates the platform-standard ObjectMapper. Used by {@link #EventSerde()} and
     * by services that need to deserialize specific event types.
     */
    public static ObjectMapper buildObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        m.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
        return m;
    }
}

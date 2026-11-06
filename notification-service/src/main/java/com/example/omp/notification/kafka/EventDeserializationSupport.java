package com.example.omp.notification.kafka;

import com.example.omp.shared.events.BaseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deserializes JSON event payloads into typed {@link BaseEvent} records.
 *
 * <p><b>Challenge:</b> Jackson can't deserialize to {@code BaseEvent.class} (it's an interface with
 * no concrete type). We need to know the concrete record class based on the event's {@code
 * eventType} field.
 *
 * <p><b>Flow:</b>
 *
 * <ol>
 *   <li>Parse the JSON into a {@link JsonNode} tree.
 *   <li>Read the {@code eventType} field.
 *   <li>Look up the concrete class from the {@link EventTypeRegistry}.
 *   <li>Convert the tree to the typed record.
 * </ol>
 *
 * <p><b>Unknown event types:</b> return null. The caller (subscriber) logs and ignores. This gives
 * forward compatibility — a new event type from a newer producer doesn't crash this consumer.
 *
 * <p><b>Malformed JSON:</b> throws {@link EventDeserializationException}. The caller logs and drops
 * the message.
 */
@Component
public class EventDeserializationSupport {

    private static final Logger log = LoggerFactory.getLogger(EventDeserializationSupport.class);

    private final ObjectMapper mapper;
    private final EventTypeRegistry registry;

    public EventDeserializationSupport(EventTypeRegistry registry) {
        this.registry = registry;
        this.mapper = com.example.omp.shared.events.EventSerde.buildObjectMapper();
    }

    /**
     * Deserializes a JSON payload.
     *
     * @return the typed event, or {@code null} if the event type is unknown
     * @throws EventDeserializationException if the JSON is malformed
     */
    public BaseEvent deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new EventDeserializationException("Empty payload");
        }

        try {
            JsonNode node = mapper.readTree(json);
            JsonNode typeNode = node.get("eventType");
            if (typeNode == null || typeNode.isNull()) {
                throw new EventDeserializationException(
                        "Missing eventType field in payload: " + truncate(json));
            }

            String eventType = typeNode.asText();
            Class<? extends BaseEvent> clazz = registry.lookup(eventType);
            if (clazz == null) {
                log.debug("Unknown event type '{}'; ignoring", eventType);
                return null;
            }

            return mapper.treeToValue(node, clazz);
        } catch (EventDeserializationException e) {
            throw e;
        } catch (Exception e) {
            throw new EventDeserializationException(
                    "Failed to deserialize event: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /**
     * Custom exception for deserialization failures. Signals "this event is broken; don't retry" to
     * the caller.
     */
    public static class EventDeserializationException extends RuntimeException {
        public EventDeserializationException(String message) {
            super(message);
        }

        public EventDeserializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

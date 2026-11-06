package com.example.omp.notification.dispatcher;

import com.example.omp.shared.events.BaseEvent;
import java.time.Instant;

/**
 * The wire format sent to WebSocket clients.
 *
 * <p>A lightweight envelope that separates routing metadata from the event payload. Clients can
 * dispatch on {@code eventType} without parsing the full payload.
 *
 * <p><b>Wire format (JSON):</b>
 *
 * <pre>
 *   {
 *     "eventType": "ORDER_SHIPPED",
 *     "occurredAt": "2025-01-15T14:23:45.123Z",
 *     "data": { ... full event ... }
 *   }
 * </pre>
 *
 * <p>The full event is included in {@code data}. Clients access domain fields via {@code
 * data.orderId}, {@code data.status}, etc.
 */
public record EventEnvelope(String eventType, Instant occurredAt, Object data) {

    /**
     * Wraps an event into its wire format.
     *
     * <p>Note: the {@code data} field is the event record itself. Jackson serializes it as a JSON
     * object with one property per record component.
     */
    public static EventEnvelope from(BaseEvent event) {
        return new EventEnvelope(event.eventType(), event.occurredAt(), event);
    }
}

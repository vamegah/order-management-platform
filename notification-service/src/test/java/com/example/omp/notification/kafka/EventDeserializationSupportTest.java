package com.example.omp.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.OrderEventType;
import com.example.omp.shared.events.OrderEvents;
import org.junit.jupiter.api.Test;

class EventDeserializationSupportTest {

    private final EventDeserializationSupport support =
            new EventDeserializationSupport(new EventTypeRegistry());

    @Test
    void deserializesOrderCreatedEvent() {
        String json =
                """
            {
              "eventId": "evt-1",
              "eventType": "ORDER_CREATED",
              "occurredAt": "2025-01-15T14:23:45.123Z",
              "schemaVersion": 1,
              "orderId": "00000000-0000-0000-0000-000000000301",
              "orderNumber": "ORD-2025-0001001",
              "customerId": "00000000-0000-0000-0000-000000000101",
              "status": "DRAFT",
              "customerEmail": "alice@omp.local",
              "currency": "USD",
              "subtotal": 1402.9200,
              "lineCount": 1
            }
            """;

        BaseEvent event = support.deserialize(json);

        assertThat(event).isInstanceOf(OrderEvents.OrderCreated.class);
        OrderEvents.OrderCreated created = (OrderEvents.OrderCreated) event;
        assertThat(created.eventType()).isEqualTo(OrderEventType.ORDER_CREATED);
        assertThat(created.orderId()).isEqualTo("00000000-0000-0000-0000-000000000301");
        assertThat(created.status()).isEqualTo("DRAFT");
    }

    @Test
    void returnsNullForUnknownEventType() {
        String json =
                """
            {"eventId":"1","eventType":"SOME_FUTURE_EVENT","occurredAt":"2025-01-15T14:23:45.123Z"}
            """;

        assertThat(support.deserialize(json)).isNull();
    }

    @Test
    void throwsForMalformedJson() {
        assertThatThrownBy(() -> support.deserialize("not json"))
                .isInstanceOf(EventDeserializationSupport.EventDeserializationException.class);
    }

    @Test
    void throwsForMissingEventType() {
        assertThatThrownBy(() -> support.deserialize("{}"))
                .isInstanceOf(EventDeserializationSupport.EventDeserializationException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void throwsForEmptyPayload() {
        assertThatThrownBy(() -> support.deserialize(""))
                .isInstanceOf(EventDeserializationSupport.EventDeserializationException.class);
    }

    @Test
    void ignoresUnknownFields() {
        String json =
                """
            {
              "eventId":"1","eventType":"ORDER_CREATED",
              "occurredAt":"2025-01-15T14:23:45.123Z",
              "schemaVersion":1,
              "orderId":"00000000-0000-0000-0000-000000000301",
              "orderNumber":"ORD-1",
              "customerId":"00000000-0000-0000-0000-000000000101",
              "status":"DRAFT",
              "customerEmail":"a@b.c","currency":"USD",
              "subtotal":100,"lineCount":1,
              "futureField":"ignored"
            }
            """;

        BaseEvent event = support.deserialize(json);
        assertThat(event).isNotNull();
    }
}

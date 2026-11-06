package com.example.omp.shared.events;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainEventReaderTest {
    private final DomainEventReader reader = new DomainEventReader(EventSerde.buildObjectMapper());

    @Test
    void readsLegacyFlatEventsAndRecoversAggregateId() {
        var result =
                reader.read(
                        """
                {"id":"evt-1","type":"payment.confirmed","paymentId":"pay-1","orderId":"order-1","timestamp":"2026-10-29T12:00:00Z"}
                """);
        assertThat(result.eventId()).isEqualTo("evt-1");
        assertThat(result.aggregateId()).isEqualTo("pay-1");
        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void preservesExplicitEnvelopeMetadataAndData() {
        var result =
                reader.read(
                        """
                {"eventId":"evt-1","eventType":"order.placed","aggregateId":"order-1","schemaVersion":1,"data":{"orderId":"order-1","extra":true}}
                """);
        assertThat(result.aggregateId()).isEqualTo("order-1");
        assertThat(result.data().get("extra").booleanValue()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "42",
                "{}",
                "{\"eventId\":123,\"eventType\":\"order.placed\"}",
                "{\"eventId\":\"x\",\"eventType\":\"order.placed\",\"data\":null}",
                "{\"eventId\":\"x\",\"eventType\":\"order.placed\",\"data\":[]}",
                "{\"eventId\":\"x\",\"eventType\":\"order.placed\",\"schemaVersion\":0}",
                "{\"eventId\":\"x\",\"eventType\":\"order.placed\",\"schemaVersion\":\"1\"}",
                "{\"eventId\":\"x\",\"eventType\":\"order.placed\",\"schemaVersion\":4294967297}"
            })
    void rejectsInvalidShapesWithoutEchoingPayloads(String json) {
        assertThatThrownBy(() -> reader.read(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unparseable domain event payload");
    }
}

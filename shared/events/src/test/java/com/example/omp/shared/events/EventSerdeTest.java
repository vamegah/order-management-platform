package com.example.omp.shared.events;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventSerdeTest {
    static Stream<BaseEvent> events() {
        return Stream.of(OrderEvents.class, InventoryEvents.class, PaymentEvents.class)
                .flatMap(container -> Arrays.stream(container.getDeclaredClasses()))
                .filter(Class::isRecord)
                .map(EventSerdeTest::fixture);
    }

    private static BaseEvent fixture(Class<?> type) {
        try {
            var components = type.getRecordComponents();
            var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var field = components[i];
                if (field.getName().equals("eventType"))
                    values[i] =
                            type.getSimpleName()
                                    .replaceAll("([a-z])([A-Z])", "$1_$2")
                                    .toUpperCase(Locale.ROOT);
                else if (field.getType() == String.class) values[i] = field.getName() + "-fixture";
                else if (field.getType() == Instant.class)
                    values[i] = Instant.parse("2026-10-29T12:00:00Z");
                else if (field.getType() == int.class) values[i] = 1;
                else if (field.getType() == BigDecimal.class) values[i] = new BigDecimal("12.3456");
                else throw new AssertionError("Add a contract fixture for " + field);
            }
            return (BaseEvent)
                    type.getDeclaredConstructor(
                                    Arrays.stream(components)
                                            .map(java.lang.reflect.RecordComponent::getType)
                                            .toArray(Class[]::new))
                            .newInstance(values);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @ParameterizedTest
    @MethodSource("events")
    void everyCanonicalEventRoundTripsAndPreservesAggregateIdentityWithAdditiveFields(
            BaseEvent event) throws Exception {
        try (var serde = new EventSerde()) {
            byte[] bytes = serde.serializer().serialize("events", event);
            ObjectNode json = (ObjectNode) serde.objectMapper().readTree(bytes);
            assertThat(json.get("aggregateId").asText()).isEqualTo(event.aggregateId());
            assertThat(json.get("occurredAt").asText()).isEqualTo(event.occurredAt().toString());
            json.put("futureField", "ignored");
            assertThat(
                            serde.deserializer()
                                    .deserialize(
                                            "events", serde.objectMapper().writeValueAsBytes(json)))
                    .isEqualTo(event);
            var envelope =
                    new DomainEventReader(serde.objectMapper())
                            .read(new String(bytes, StandardCharsets.UTF_8));
            assertThat(envelope.aggregateId()).isEqualTo(event.aggregateId());
        }
    }

    @Test
    void tombstonesRoundTrip() {
        try (var serde = new EventSerde()) {
            assertThat(serde.serializer().serialize("events", null)).isNull();
            assertThat(serde.deserializer().deserialize("events", null)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "{",
                "{}",
                "{\"eventType\":123}",
                "{\"eventType\":\"java.lang.Runtime\"}",
                "{\"eventType\":\"PAYMENT_CONFIRMED\"}"
            })
    void malformedUnknownAndIncompleteEventsUseKafkaSerializationErrors(String json) {
        try (var serde = new EventSerde()) {
            assertThatThrownBy(
                            () ->
                                    serde.deserializer()
                                            .deserialize(
                                                    "events",
                                                    json.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SerializationException.class);
        }
    }

    @Test
    void breakingSchemaVersionsAreRejected() throws Exception {
        try (var serde = new EventSerde()) {
            var json =
                    (ObjectNode)
                            serde.objectMapper().valueToTree(events().findFirst().orElseThrow());
            json.put("schemaVersion", 2);
            assertThatThrownBy(
                            () ->
                                    serde.deserializer()
                                            .deserialize(
                                                    "events",
                                                    serde.objectMapper().writeValueAsBytes(json)))
                    .isInstanceOf(SerializationException.class);
        }
    }
}

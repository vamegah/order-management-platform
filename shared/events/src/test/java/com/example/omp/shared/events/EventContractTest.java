package com.example.omp.shared.events;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventContractTest {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            EventSerde.buildObjectMapper();

    private ObjectNode flat() {
        ObjectNode n = mapper.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", "ORDER_CREATED");
        n.put("occurredAt", Instant.now().toString());
        n.put("schemaVersion", 1);
        n.put("orderId", UUID.randomUUID().toString());
        n.put("customerId", UUID.randomUUID().toString());
        n.put("status", "DRAFT");
        return n;
    }

    @Test
    void oldFlatAndAdditiveEnvelopeConsumersInteroperate() throws Exception {
        var old = flat();
        var envelope = old.deepCopy();
        envelope.put("aggregateId", old.path("orderId").asText());
        envelope.set("data", old.deepCopy());
        envelope.put("optionalFutureField", "safe");
        var legacy = mapper.treeToValue(envelope, OrderEvents.OrderCreated.class);
        var current =
                EventContract.typed(
                        EventContract.validate(
                                envelope.toString(), KafkaTopics.ORDERS_EVENTS_ORDER));
        assertThat(current).isEqualTo(legacy);
        assertThat(
                        EventContract.typed(
                                EventContract.validate(
                                        old.toString(), KafkaTopics.ORDERS_EVENTS_ORDER)))
                .isEqualTo(legacy);
    }

    @Test
    void publishedDottedPaymentAliasNormalizesForLiveDelivery() {
        var n = flat();
        n.put("eventType", "payment.confirmed");
        n.put("paymentId", n.path("orderId").asText());
        var event =
                EventContract.typed(
                        EventContract.validate(n.toString(), KafkaTopics.PAYMENTS_EVENTS_PAYMENT));
        assertThat(event).isInstanceOf(PaymentEvents.PaymentConfirmed.class);
        assertThat(event.eventType()).isEqualTo("PAYMENT_CONFIRMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "occurredAt", "customerId", "orderId"})
    void missingIdentityAndCorrelationCannotReachConsumers(String field) {
        var n = flat();
        n.remove(field);
        assertThatThrownBy(
                        () -> EventContract.validate(n.toString(), KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(EventContract.InvalidEventException.class);
    }

    @Test
    void unsupportedVersionUnknownTypeWrongTopicAndAggregateMismatchFailClosed() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var n = flat();
            String topic = KafkaTopics.ORDERS_EVENTS_ORDER;
            switch (scenario) {
                case 0 -> n.put("schemaVersion", 2);
                case 1 -> n.put("eventType", "UNSUPPORTED");
                case 2 -> topic = KafkaTopics.PAYMENTS_EVENTS_PAYMENT;
                default -> n.put("aggregateId", UUID.randomUUID().toString());
            }
            String selected = topic;
            assertThatThrownBy(() -> EventContract.validate(n.toString(), selected))
                    .isInstanceOf(EventContract.InvalidEventException.class)
                    .hasMessage("Event does not satisfy the supported contract");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{}", "\"secret\""})
    void malformedInputHasNoPayloadInItsPublicFailure(String input) {
        assertThatThrownBy(() -> EventContract.validate(input, KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(EventContract.InvalidEventException.class)
                .hasMessage("Event does not satisfy the supported contract");
    }

    @Test
    void flatCompatibilityFieldsCannotContradictEnvelopeOwnership() {
        var flat = flat();
        var envelope = flat.deepCopy();
        envelope.set("data", flat.deepCopy());
        envelope.put("customerId", UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                EventContract.validate(
                                        envelope.toString(), KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(EventContract.InvalidEventException.class);
    }

    @Test
    void invalidPayloadFieldTypesAreQuarantinedBeforeEitherConsumerRuns() {
        var n = flat();
        n.put("eventType", "RESERVATION_CREATED");
        n.put("reservationId", UUID.randomUUID().toString());
        n.put("sku", "SKU-1");
        n.put("quantity", -1);
        assertThatThrownBy(
                        () ->
                                EventContract.validate(
                                        n.toString(), KafkaTopics.INVENTORY_EVENTS_RESERVATION))
                .isInstanceOf(EventContract.InvalidEventException.class);
        n.put("quantity", 1);
        n.put("sku", 42);
        assertThatThrownBy(
                        () ->
                                EventContract.validate(
                                        n.toString(), KafkaTopics.INVENTORY_EVENTS_RESERVATION))
                .isInstanceOf(EventContract.InvalidEventException.class);
        var order = flat();
        order.put("status", 42);
        String invalid = order.toString();
        assertThatThrownBy(() -> EventContract.validate(invalid, KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(EventContract.InvalidEventException.class);
    }

    @Test
    void existingWarehouseCodesSurviveTheProducerAndConsumerContract() throws Exception {
        var event =
                new InventoryEvents.ReservationCreated(
                        UUID.randomUUID().toString(),
                        "RESERVATION_CREATED",
                        java.time.Instant.now(),
                        1,
                        UUID.randomUUID().toString(),
                        "SKU-1",
                        "WH-1",
                        UUID.randomUUID().toString(),
                        1);
        var flat = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(event);
        var envelope = flat.deepCopy();
        envelope.put("aggregateId", event.aggregateId());
        envelope.set("data", flat);
        assertThat(
                        EventContract.typed(
                                EventContract.validate(
                                        envelope.toString(),
                                        KafkaTopics.INVENTORY_EVENTS_RESERVATION)))
                .isEqualTo(event);
        assertThat(
                        EventContract.typed(
                                EventContract.validate(
                                        mapper.writeValueAsString(event),
                                        KafkaTopics.INVENTORY_EVENTS_RESERVATION)))
                .isEqualTo(event);
    }
}

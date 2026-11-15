package com.example.omp.notification.durable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.matcher.SubscriptionMatcher;
import com.example.omp.shared.events.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DomainEventIngestionTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final DispatchQueue queue = mock(DispatchQueue.class);
    final SubscriptionMatcher matcher = mock(SubscriptionMatcher.class);
    final DomainEventIngestion ingestion = new DomainEventIngestion(jdbc, queue, matcher);
    final UUID id = UUID.randomUUID();

    String event(String type, String resource) throws Exception {
        var data = new LinkedHashMap<String, Object>();
        data.put("eventId", id.toString());
        data.put("eventType", type);
        data.put("occurredAt", Instant.now().toString());
        data.put("schemaVersion", 1);
        data.put(resource, UUID.randomUUID().toString());
        data.put("customerId", UUID.randomUUID().toString());
        data.put("sku", "SKU-1");
        data.put("status", "CONFIRMED");
        return new ObjectMapper().writeValueAsString(data);
    }

    @Test
    void liveOrderPaymentAndInventoryEventsUseTheirOwnChannels() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        for (var test :
                List.of(
                        new String[] {
                            "ORDER_CREATED",
                            "orderId",
                            KafkaTopics.ORDERS_EVENTS_ORDER,
                            "notifications:orders"
                        },
                        new String[] {
                            "PAYMENT_CONFIRMED",
                            "paymentId",
                            KafkaTopics.PAYMENTS_EVENTS_PAYMENT,
                            "notifications:payments"
                        },
                        new String[] {
                            "RESERVATION_CREATED",
                            "reservationId",
                            KafkaTopics.INVENTORY_EVENTS_RESERVATION,
                            "notifications:inventory"
                        })) {
            ingestion.accept("live", event(test[0], test[1]), test[2]);
            verify(queue)
                    .enqueue(
                            eq(id.toString()),
                            eq("REDIS"),
                            eq(test[3]),
                            anyString(),
                            contains(test[0]),
                            any());
        }
        verifyNoInteractions(matcher);
    }

    @Test
    void identicalRedeliveryDoesNotRepeatFanout() throws Exception {
        String p = event("ORDER_CREATED", "orderId");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("live"), eq(id)))
                .thenReturn(DomainEventIngestion.hash(p));
        ingestion.accept("live", p, KafkaTopics.ORDERS_EVENTS_ORDER);
        verifyNoInteractions(queue, matcher);
    }

    @Test
    void reusedIdentityWithDifferentContentIsRejected() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("live"), eq(id)))
                .thenReturn("different");
        assertThatThrownBy(
                        () ->
                                ingestion.accept(
                                        "live",
                                        event("ORDER_CREATED", "orderId"),
                                        KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queue, matcher);
    }

    @Test
    void webhookFanoutUsesValidatedEnvelopeIdentity() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ingestion.accept(
                "webhook", event("ORDER_CREATED", "orderId"), KafkaTopics.ORDERS_EVENTS_ORDER);
        verify(matcher).matchAndPublish(eq(id.toString()), eq("ORDER_CREATED"), any());
        verifyNoInteractions(queue);
    }

    @Test
    void invalidEventsCannotWriteInboxOrDispatchState() {
        assertThatThrownBy(
                        () ->
                                ingestion.accept(
                                        "live", "malformed", KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, queue, matcher);
    }

    @Test
    void databaseFailurePreventsAnyForwarding() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(
                        () ->
                                ingestion.accept(
                                        "live",
                                        event("ORDER_CREATED", "orderId"),
                                        KafkaTopics.ORDERS_EVENTS_ORDER))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(queue, matcher);
    }
}

package com.example.omp.notification.webhook.matcher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.durable.DispatchQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookDeliveryPublisherTest {
    final DispatchQueue queue = mock(DispatchQueue.class);
    final ObjectMapper mapper = new ObjectMapper();
    final WebhookDeliveryPublisher publisher = new WebhookDeliveryPublisher(queue, mapper);
    final DeliveryMessage message =
            new DeliveryMessage(
                    UUID.randomUUID(), UUID.randomUUID(), "event", "PAYMENT_CONFIRMED", 1, null);

    @Test
    void stableDeliveryIdentityIsDurablyEnqueuedWithoutDirectBrokerPublication() throws Exception {
        publisher.publish(message);
        verify(queue)
                .enqueue(
                        anyString(),
                        eq("KAFKA"),
                        eq("webhooks.outbound.deliveries"),
                        eq(message.deliveryId().toString()),
                        eq(mapper.writeValueAsString(message)),
                        any(Instant.class));
    }

    @Test
    void delayedRetryIsStoredWithItsDueTimeInsteadOfSleepingOnAPartition() {
        long due = System.currentTimeMillis() + 60000;
        var retry =
                new DeliveryMessage(
                        message.deliveryId(),
                        message.subscriptionId(),
                        message.eventId(),
                        message.eventType(),
                        2,
                        due);
        publisher.publish("retry-topic", retry);
        verify(queue)
                .enqueue(
                        anyString(),
                        eq("KAFKA"),
                        eq("retry-topic"),
                        eq(message.deliveryId().toString()),
                        anyString(),
                        eq(Instant.ofEpochMilli(due)));
    }

    @Test
    void databaseFailurePropagatesToTheConsumingTransaction() {
        doThrow(new IllegalStateException("database down"))
                .when(queue)
                .enqueue(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidDeliveryCannotBeEnqueued() {
        assertThatThrownBy(
                        () ->
                                publisher.publish(
                                        new DeliveryMessage(null, null, "bad", "bad", 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queue);
    }
}

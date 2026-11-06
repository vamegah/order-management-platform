package com.example.omp.notification.webhook.matcher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class WebhookDeliveryPublisherTest {
    @SuppressWarnings("unchecked")
    final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    final ObjectMapper mapper = new ObjectMapper();
    final WebhookDeliveryPublisher publisher = new WebhookDeliveryPublisher(kafka, mapper);
    final DeliveryMessage message =
            new DeliveryMessage(
                    UUID.randomUUID(), UUID.randomUUID(), "event-1", "payment.confirmed", 1, null);

    @Test
    void acknowledgedPublishUsesStableDeliveryKeyAndRoundTripsEnvelope() throws Exception {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish(message);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(kafka)
                .send(
                        eq(WebhookKafkaTopics.DELIVERIES),
                        eq(message.deliveryId().toString()),
                        payload.capture());
        assertThat(mapper.readValue(payload.getValue(), DeliveryMessage.class)).isEqualTo(message);
    }

    @Test
    void asynchronousBrokerRejectionPropagatesToConsumingListener() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> publisher.publish(message))
                .hasRootCauseMessage("broker unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingAcknowledgmentHasABoundedWait() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(10, TimeUnit.SECONDS)).thenThrow(new TimeoutException("no acknowledgment"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        assertThatThrownBy(() -> publisher.publish(message))
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionIsPreservedForContainerShutdown() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        try {
            assertThatThrownBy(() -> publisher.publish(message))
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

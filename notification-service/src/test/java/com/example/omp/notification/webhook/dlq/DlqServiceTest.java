package com.example.omp.notification.webhook.dlq;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.matcher.*;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DlqServiceTest {
    final WebhookDlqRepository repository = mock(WebhookDlqRepository.class);
    final WebhookDeliveryPublisher publisher = mock(WebhookDeliveryPublisher.class);
    final WebhookMetrics metrics = mock(WebhookMetrics.class);
    final ObjectMapper mapper = new ObjectMapper();
    final DlqService service = new DlqService(repository, publisher, mapper, metrics);
    final DeliveryMessage message =
            new DeliveryMessage(
                    UUID.randomUUID(), UUID.randomUUID(), "event", "payment.failed", 6, null);

    @Test
    void failedPersistencePropagatesInsteadOfBeingAcknowledged() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.persist(message, "failure", "FAILED_PERMANENT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verifyNoInteractions(metrics);
    }

    @Test
    void replayDoesNotMarkEntryWhenBrokerRejectsPublication() throws Exception {
        var entry = new WebhookDlqEntry();
        entry.setPayload(mapper.writeValueAsString(message));
        when(repository.findByDeliveryId(message.deliveryId())).thenReturn(Optional.of(entry));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher)
                .publish(anyString(), any());
        assertThatThrownBy(() -> service.replay(message.deliveryId(), "admin"))
                .hasMessage("broker unavailable");
        assertThat(entry.getReplayedAt()).isNull();
        verify(repository, never()).save(any());
        verifyNoInteractions(metrics);
    }

    @Test
    void corruptReplayPayloadDoesNotPublishOrMarkEntry() {
        var entry = new WebhookDlqEntry();
        entry.setPayload("not-json");
        when(repository.findByDeliveryId(message.deliveryId())).thenReturn(Optional.of(entry));
        assertThatThrownBy(() -> service.replay(message.deliveryId(), "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt DLQ payload");
        assertThat(entry.getReplayedAt()).isNull();
        verifyNoInteractions(publisher, metrics);
    }

    @Test
    void safetyNetConsumerPropagatesDatabaseFailureForKafkaRetry() throws Exception {
        var failingService = mock(DlqService.class);
        var consumer = new DlqConsumer(failingService, repository, mapper);
        doThrow(new IllegalStateException("database unavailable"))
                .when(failingService)
                .persist(any(), anyString(), anyString());
        assertThatThrownBy(() -> consumer.onDlq(mapper.writeValueAsString(message)))
                .hasMessage("database unavailable");
    }
}

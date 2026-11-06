package com.example.omp.shared.events.publisher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.PaymentEventType;
import com.example.omp.shared.events.PaymentEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class KafkaEventPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, BaseEvent> kafka = mock(KafkaTemplate.class);

    private final BaseEvent event =
            new PaymentEvents.PaymentConfirmed(
                    "evt-1",
                    PaymentEventType.PAYMENT_CONFIRMED,
                    Instant.now(),
                    1,
                    "pay-1",
                    "order-1",
                    "customer-1",
                    "CONFIRMED",
                    "provider-1");

    private class Publisher extends KafkaEventPublisher {
        Publisher() {
            super(kafka, "payments", Duration.ofMillis(2));
        }
    }

    @AfterEach
    void clearThread() {
        Thread.interrupted();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void successfulSendUsesAggregateKeyAndReturnsAfterAcknowledgement() {
        @SuppressWarnings("unchecked")
        SendResult<String, BaseEvent> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenReturn(mock(RecordMetadata.class));
        when(kafka.send("payments", "pay-1", event))
                .thenReturn(CompletableFuture.completedFuture(result));
        new Publisher().publish(event);
        verify(kafka).send("payments", "pay-1", event);
    }

    @Test
    void asynchronousBrokerFailureIsReportedToTheCaller() {
        var failure = new IllegalStateException("broker unavailable");
        when(kafka.send("payments", "pay-1", event))
                .thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> new Publisher().publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasCause(failure);
    }

    @Test
    void synchronousFailureAndMissingAcknowledgementAreReported() {
        when(kafka.send("payments", "pay-1", event))
                .thenThrow(new IllegalArgumentException("serialization"));
        assertThatThrownBy(() -> new Publisher().publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        doReturn(new CompletableFuture<>()).when(kafka).send("payments", "pay-1", event);
        assertThatThrownBy(() -> new Publisher().publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void preservesInterruption() {
        when(kafka.send("payments", "pay-1", event)).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> new Publisher().publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void schedulerDistinguishesAnActualTransactionFromSynchronizationOnly() {
        var scheduler = new TransactionEventScheduler();
        var callback = mock(Runnable.class);
        scheduler.afterCommit(callback);
        verify(callback).run();
        reset(callback);
        TransactionSynchronizationManager.initSynchronization();
        scheduler.afterCommit(callback);
        verify(callback).run();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        reset(callback);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        scheduler.afterCommit(callback);
        verifyNoInteractions(callback);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        verify(callback).run();
    }
}

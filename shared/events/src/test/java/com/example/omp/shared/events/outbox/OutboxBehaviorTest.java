package com.example.omp.shared.events.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.shared.events.PaymentEventType;
import com.example.omp.shared.events.PaymentEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OutboxBehaviorTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxMetrics metrics = mock(OutboxMetrics.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final OutboxDrainerProperties properties = new OutboxDrainerProperties();
    private final OutboxDrainer drainer = new OutboxDrainer(repository, properties, metrics, kafka);

    @AfterEach
    void resetThreadContext() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    private OutboxEvent row(String id) {
        return new OutboxEvent(
                id,
                "PAYMENT",
                PaymentEventType.PAYMENT_CONFIRMED,
                "payments",
                "{\"id\":\"" + id + "\"}");
    }

    @Test
    void writerRequiresReadWriteTransactionEvenWhenCalledWithoutAProxy() {
        var writer = new OutboxWriter(repository);
        var event =
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
        assertThatThrownBy(() -> writer.write(event, "payments"))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> writer.write(event, "payments"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var stored = writer.write(event, "payments");
        assertThat(stored.getAggregateId()).isEqualTo("pay-1");
        assertThat(stored.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(stored.getPayload())
                .contains("\"eventId\":\"evt-1\"", "\"aggregateId\":\"pay-1\"");
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(repository).save(stored);
    }

    @Test
    void timeoutRetriesOneEventWithoutPreventingAnotherFromPublishing() {
        properties.setPublishTimeout(Duration.ofMillis(2));
        properties.setMaxAttempts(2);
        var first = row("first");
        var second = row("second");
        when(repository.claimPendingBatch(100)).thenReturn(List.of(first, second), List.of(first));
        when(kafka.send("payments", "first", first.getPayload()))
                .thenReturn(new CompletableFuture<>());
        when(kafka.send("payments", "second", second.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        drainer.drain();
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        drainer.drain();
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(first.getAttempts()).isEqualTo(2);
        assertThat(first.getLastError()).contains("TimeoutException");
        verify(metrics, times(2)).incrementError();
        verify(metrics).incrementPublished();
    }

    @Test
    void interruptionIsPreservedAndRemainingBatchIsLeftPending() throws Exception {
        var first = row("first");
        var second = row("second");
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> send = mock(CompletableFuture.class);
        when(send.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new InterruptedException("shutdown"));
        when(repository.claimPendingBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send("payments", "first", first.getPayload())).thenReturn(send);
        drainer.drain();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getAttempts()).isZero();
        verify(kafka, never()).send("payments", "second", second.getPayload());
    }

    @Test
    void disabledDrainingAndCleanupDoNotTouchDependencies() {
        properties.setEnabled(false);
        drainer.drain();
        drainer.cleanup();
        verifyNoInteractions(repository, kafka, metrics);
    }

    @Test
    void emptyBatchIsQuietAndCleanupUsesConfiguredPublishedRetention() {
        when(repository.claimPendingBatch(100)).thenReturn(List.of());
        drainer.drain();
        verifyNoInteractions(kafka, metrics);
        properties.setPublishedRetention(Duration.ofDays(1));
        var before = Instant.now().minus(Duration.ofDays(1));
        drainer.cleanup();
        var cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).deletePublishedOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, Instant.now().minus(Duration.ofDays(1)));
    }

    @Test
    void publishedRowsCannotBecomeFailedAndRetriesHaveAValidThreshold() {
        var event = row("pay-1");
        assertThatThrownBy(() -> event.recordAttemptFailure("error", 0))
                .isInstanceOf(IllegalArgumentException.class);
        event.recordAttemptFailure("x".repeat(2000), 2);
        assertThat(event.getLastError()).hasSize(1000);
        event.markPublished();
        var publishedAt = event.getPublishedAt();
        event.markPublished();
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(event.getLastError()).isNull();
        assertThatThrownBy(() -> event.recordAttemptFailure("late failure", 2))
                .isInstanceOf(IllegalStateException.class);
        var failed = row("pay-2");
        failed.recordAttemptFailure(null, 1);
        assertThatThrownBy(failed::markPublished).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidStorageMetadataAndWorkerSettingsFailBeforeUse() {
        assertThatThrownBy(() -> new OutboxEvent("", "PAYMENT", "event", "topic", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxEvent("id", "PAYMENT", "event", "x".repeat(256), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBatchSize(1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxAttempts(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setPublishTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setPollInterval(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setCleanupInterval(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setPublishedRetention(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

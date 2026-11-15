package com.example.omp.notification.durable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

class DispatchDrainerTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @SuppressWarnings("unchecked")
    final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final DispatchDrainer drainer = new DispatchDrainer(jdbc, kafka, redis, metrics, true);

    Map<String, Object> row(String transport, int attempts) {
        return Map.of(
                "id",
                UUID.randomUUID(),
                "transport",
                transport,
                "destination",
                "destination",
                "message_key",
                "key",
                "payload",
                "{}",
                "attempts",
                attempts);
    }

    @Test
    void brokerAcknowledgmentPrecedesPublishedState() {
        var record = row("KAFKA", 0);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(record));
        when(kafka.send("destination", "key", "{}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        drainer.drain();
        var order = inOrder(kafka, jdbc);
        order.verify(kafka).send("destination", "key", "{}");
        order.verify(jdbc).update(contains("state='PUBLISHED'"), eq(record.get("id")));
        assertThat(metrics.get("notification.dispatch.published").counter().count()).isEqualTo(1);
    }

    @Test
    void boundedRetriesRetainFailedWorkForOperatorRecovery() {
        var record = row("KAFKA", 9);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(record));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("secret")));
        drainer.drain();
        verify(jdbc)
                .update(
                        contains("attempts=?"),
                        eq(10),
                        eq("FAILED"),
                        eq("TRANSPORT_FAILURE"),
                        any(),
                        eq(record.get("id")));
        verify(jdbc, never()).update(contains("state='PUBLISHED'"), (Object) any());
    }

    @Test
    void redisOutageRetainsPendingWorkAndHealthyPublishAcknowledgesHandoff() {
        var record = row("REDIS", 0);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(record));
        when(redis.convertAndSend(anyString(), any()))
                .thenThrow(new IllegalStateException("unavailable"));
        drainer.drain();
        verify(jdbc)
                .update(
                        contains("attempts=?"),
                        eq(1),
                        eq("PENDING"),
                        eq("TRANSPORT_FAILURE"),
                        any(),
                        eq(record.get("id")));
        doReturn(2L).when(redis).convertAndSend(anyString(), any());
        drainer.drain();
        verify(jdbc).update(contains("state='PUBLISHED'"), eq(record.get("id")));
    }

    @Test
    void disabledWorkerDoesNotReadOrDeleteAnything() {
        var disabled = new DispatchDrainer(jdbc, kafka, redis, metrics, false);
        disabled.drain();
        disabled.cleanup();
        verifyNoInteractions(jdbc, kafka, redis);
    }

    @Test
    void cleanupPreservesUnresolvedWorkAndAuditIdentities() {
        drainer.cleanup();
        verify(jdbc, times(2)).update(anyString());
    }
}

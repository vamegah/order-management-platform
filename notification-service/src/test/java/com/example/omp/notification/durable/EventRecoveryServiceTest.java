package com.example.omp.notification.durable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class EventRecoveryServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final DomainEventIngestion ingestion = mock(DomainEventIngestion.class);
    final DispatchQueue queue = mock(DispatchQueue.class);
    final EventRecoveryService service = new EventRecoveryService(jdbc, ingestion, queue);
    final UUID id = UUID.randomUUID();

    Map<String, Object> record(String consumer) {
        return Map.of(
                "id",
                id,
                "consumer_id",
                consumer,
                "source_topic",
                "orders.events.order",
                "payload",
                "original");
    }

    @Test
    void repeatedReplayIsRejectedBeforeAnotherEffect() {
        when(jdbc.queryForList(anyString(), eq(id)))
                .thenReturn(List.of(Map.of("replayed_at", new Date())));
        assertThatThrownBy(() -> service.replay(id, "operator", "reviewed", null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ingestion, queue);
    }

    @Test
    void missingRecordIsNotReplayedOrInspected() {
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of());
        assertThatThrownBy(() -> service.replay(id, "operator", "reviewed", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.inspect(id)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ingestion, queue);
    }

    @Test
    void correctedDomainRecordKeepsOriginalConsumerAndSourceTopic() {
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(record("live")));
        service.replay(id, "operator", "verified corrected source", "corrected");
        verify(ingestion).accept("live", "corrected", "orders.events.order");
        verify(jdbc)
                .update(
                        contains("event_recovery_audit"),
                        any(),
                        eq(id),
                        eq("QUARANTINE_REPLAY"),
                        eq("operator"),
                        eq("verified corrected source"),
                        eq(DomainEventIngestion.hash("corrected")));
    }

    @Test
    void invalidReplayCannotMarkTheRecordOrWriteItsAudit() {
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(record("webhook")));
        doThrow(new IllegalArgumentException())
                .when(ingestion)
                .accept(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.replay(id, "operator", "reviewed", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unknownConsumerAndMalformedDeliveryStayUnresolved() {
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(record("unknown")));
        assertThatThrownBy(() -> service.replay(id, "operator", "reviewed", null))
                .isInstanceOf(ResponseStatusException.class);
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(record("delivery")));
        assertThatThrownBy(() -> service.replay(id, "operator", "reviewed", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(queue);
    }

    @Test
    void onlyFailedDispatchesCanBeReset() {
        when(jdbc.update(contains("UPDATE notification_dispatch"), eq(id))).thenReturn(0);
        assertThatThrownBy(() -> service.retry(id, "operator", "repaired"))
                .isInstanceOf(ResponseStatusException.class);
        when(jdbc.update(contains("UPDATE notification_dispatch"), eq(id))).thenReturn(1);
        service.retry(id, "operator", "repaired");
        verify(jdbc)
                .update(
                        contains("event_recovery_audit"),
                        any(),
                        eq(id),
                        eq("DISPATCH_RETRY"),
                        eq("operator"),
                        eq("repaired"),
                        isNull());
    }

    @Test
    void inspectionReturnsOnlyTheStoredDiagnosticProjection() {
        var metadata = Map.<String, Object>of("id", id, "failure_code", "CONTRACT_REJECTED");
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(metadata));
        assertThat(service.inspect(id)).isEqualTo(metadata).doesNotContainKey("payload");
    }
}

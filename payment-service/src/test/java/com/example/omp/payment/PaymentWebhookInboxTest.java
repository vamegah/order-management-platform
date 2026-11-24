package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.provider.ProviderWebhookEvent;
import com.example.omp.payment.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentWebhookInboxTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ObjectMapper mapper = new ObjectMapper();
    final PaymentProviderEventService events = mock(PaymentProviderEventService.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final PaymentWebhookInbox inbox = new PaymentWebhookInbox(jdbc, mapper, events, metrics);

    ProviderWebhookEvent event() throws Exception {
        return new ProviderWebhookEvent(
                "evt_safe",
                "payment_intent.succeeded",
                mapper.readTree(
                        "{\"id\":\"pi_safe\",\"amount\":100,\"currency\":\"usd\",\"client_secret\":\"sensitive\",\"billing_details\":{\"name\":\"private\"},\"metadata\":{\"payment_id\":\"local-id\",\"private_note\":\"sensitive\"},\"last_payment_error\":{\"decline_code\":\"bad code\",\"message\":\"sensitive\"}}"),
                "pi_safe");
    }

    @Test
    void storesMinimalCorrelationAndDigestBeforeAcknowledging() throws Exception {
        var event = event();
        when(jdbc.update(
                        startsWith("INSERT INTO payments.provider_event("),
                        any(UUID.class),
                        eq("stripe"),
                        eq(event.eventId()),
                        eq(event.eventType()),
                        anyString(),
                        eq(event.paymentRef()),
                        anyString()))
                .thenReturn(1);
        assertThat(inbox.accept("stripe", event, new byte[] {1, 2, 3})).isEqualTo("accepted");
        var payload = ArgumentCaptor.forClass(String.class);
        var digest = ArgumentCaptor.forClass(String.class);
        verify(jdbc)
                .update(
                        startsWith("INSERT INTO payments.provider_event("),
                        any(UUID.class),
                        eq("stripe"),
                        eq(event.eventId()),
                        eq(event.eventType()),
                        payload.capture(),
                        eq(event.paymentRef()),
                        digest.capture());
        assertThat(payload.getValue())
                .doesNotContain("sensitive", "private_note", "billing_details", "client_secret");
        assertThat(
                        mapper.readTree(payload.getValue())
                                .path("last_payment_error")
                                .path("decline_code")
                                .asText())
                .isEqualTo("provider_declined");
        assertThat(digest.getValue())
                .isEqualTo(
                        HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(new byte[] {1, 2, 3})));
    }

    @Test
    void mismatchedIdentityDigestIsAConflictAndCannotApplyMoney() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("stripe"), eq("evt_safe")))
                .thenReturn("different-digest");
        assertThatThrownBy(() -> inbox.accept("stripe", event(), new byte[] {1}))
                .isInstanceOf(PaymentWebhookInbox.ConflictingEvent.class);
        verifyNoInteractions(events);
    }

    @Test
    void oldIdentityWithoutDigestAcknowledgesWithoutRepeatingAnEffect() throws Exception {
        assertThat(inbox.accept("stripe", event(), new byte[] {1})).isEqualTo("duplicate");
        verifyNoInteractions(events);
    }

    @Test
    void invalidReceiptNeverTouchesStorage() {
        assertThatThrownBy(() -> inbox.accept("stripe", null, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, events);
    }

    @Test
    void processingUsesTheStoredSanitizedContractAndUpdatesCompletion() throws Exception {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(startsWith("SELECT * FROM payments.provider_event"), eq(id)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "provider",
                                        "stripe",
                                        "provider_event_id",
                                        "evt_safe",
                                        "event_type",
                                        "payment_intent.succeeded",
                                        "payload",
                                        "{\"id\":\"pi_safe\"}",
                                        "payment_ref",
                                        "pi_safe")));
        assertThat(inbox.process(id)).isTrue();
        verify(events)
                .processRegistered(
                        eq(id),
                        eq("stripe"),
                        argThat(event -> event.paymentRef().equals("pi_safe")));
        verify(jdbc).update(startsWith("UPDATE payments.provider_event SET processed_at="), eq(id));
    }

    @Test
    void unavailableClaimsAndMalformedStoredEventsCannotApplyMoney() {
        UUID id = UUID.randomUUID();
        assertThat(inbox.process(id)).isFalse();
        when(jdbc.queryForList(startsWith("SELECT * FROM payments.provider_event"), eq(id)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "provider",
                                        "stripe",
                                        "provider_event_id",
                                        "evt_safe",
                                        "event_type",
                                        "payment_intent.succeeded",
                                        "payload",
                                        "not-json",
                                        "payment_ref",
                                        "pi_safe")));
        assertThatThrownBy(() -> inbox.process(id)).hasMessage("INVALID_STORED_EVENT");
        verifyNoInteractions(events);
    }

    @Test
    void replayRequiresAttentionAndAnInvestigationReason() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> inbox.replay(id, "admin", "investigated"))
                .isInstanceOf(PaymentWebhookInbox.ConflictingEvent.class);
        when(jdbc.queryForList(startsWith("SELECT id,status"), eq(id)))
                .thenReturn(List.of(Map.of("id", id, "status", "ATTENTION")));
        assertThatThrownBy(() -> inbox.replay(id, "admin", " "))
                .isInstanceOf(IllegalArgumentException.class);
        inbox.replay(id, "admin", "Provider correlation investigated");
        verify(jdbc)
                .update(
                        startsWith("INSERT INTO payments.provider_event_replay_audit"),
                        eq(id),
                        eq("admin"),
                        eq("Provider correlation investigated"));
    }

    @Test
    void workerRetriesOneFailedEventAndContinuesTheBatch() {
        var work = mock(PaymentWebhookInbox.class);
        UUID failed = UUID.randomUUID(), passed = UUID.randomUUID();
        when(work.due()).thenReturn(List.of(failed, passed));
        when(work.process(failed)).thenThrow(new IllegalStateException("unresolved"));
        when(work.process(passed)).thenReturn(true);
        var worker = new PaymentWebhookWorker(work, metrics, true);
        worker.drain();
        verify(work).failed(failed);
        verify(work).process(passed);
        assertThat(metrics.get("payment.webhook.inbox.retries").counter().count()).isEqualTo(1);
        assertThat(metrics.get("payment.webhook.inbox.processed").counter().count()).isEqualTo(1);
        worker.cleanup();
        verify(work).cleanup();
    }
}

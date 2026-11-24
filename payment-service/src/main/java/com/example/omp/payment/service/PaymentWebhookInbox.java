package com.example.omp.payment.service;

import com.example.omp.payment.provider.ProviderWebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verified, minimal provider work commits before acknowledgment. Financial dedup IDs are retained.
 */
@Service
public class PaymentWebhookInbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PaymentProviderEventService events;

    public PaymentWebhookInbox(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PaymentProviderEventService events,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
        metrics.gauge(
                "payment.webhook.inbox.pending",
                jdbc,
                j ->
                        j.queryForObject(
                                "SELECT count(*) FROM payments.provider_event WHERE status='RECEIVED'",
                                Long.class));
        metrics.gauge(
                "payment.webhook.inbox.attention",
                jdbc,
                j ->
                        j.queryForObject(
                                "SELECT count(*) FROM payments.provider_event WHERE status='ATTENTION'",
                                Long.class));
        metrics.gauge(
                "payment.webhook.inbox.oldest.seconds",
                jdbc,
                j ->
                        j.queryForObject(
                                "SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM payments.provider_event WHERE status='RECEIVED'",
                                Double.class));
    }

    @Transactional(timeout = 2)
    public String accept(String provider, ProviderWebhookEvent event, byte[] raw) {
        if (event == null
                || event.eventId() == null
                || event.eventId().isBlank()
                || event.eventId().length() > 255
                || event.eventType() == null
                || event.eventType().length() > 128
                || event.paymentRef() == null
                || event.paymentRef().length() > 255
                || (event.data() == null || !event.data().isObject()))
            throw new IllegalArgumentException("Invalid provider event");
        final String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        jdbc.execute("SET LOCAL statement_timeout='1500ms'");
        int inserted =
                jdbc.update(
                        "INSERT INTO payments.provider_event(id,provider,provider_event_id,event_type,payload,status,payment_ref,body_sha256,created_at) VALUES(?,?,?,?,?,'RECEIVED',?,?,now()) ON CONFLICT(provider,provider_event_id) DO NOTHING",
                        UUID.randomUUID(),
                        provider,
                        event.eventId(),
                        event.eventType(),
                        safeData(event.data()).toString(),
                        event.paymentRef(),
                        hash);
        if (inserted == 1) return "accepted";
        String previous =
                jdbc.queryForObject(
                        "SELECT body_sha256 FROM payments.provider_event WHERE provider=? AND provider_event_id=?",
                        String.class,
                        provider,
                        event.eventId());
        if (previous != null && !previous.equals(hash)) throw new ConflictingEvent();
        return "duplicate";
    }

    private com.fasterxml.jackson.databind.JsonNode safeData(
            com.fasterxml.jackson.databind.JsonNode source) {
        var safe = mapper.createObjectNode();
        for (String key : List.of("id", "amount", "currency", "payment_intent", "status"))
            if (source.has(key)) safe.set(key, source.get(key));
        var metadata = safe.putObject("metadata");
        for (String key : List.of("payment_id", "refund_id", "order_id", "customer_id"))
            if (source.path("metadata").has(key))
                metadata.set(key, source.path("metadata").get(key));
        if (source.has("last_payment_error")) {
            String code =
                    source.path("last_payment_error")
                            .path("decline_code")
                            .asText("provider_declined");
            safe.putObject("last_payment_error")
                    .put(
                            "decline_code",
                            code.matches("[a-zA-Z0-9_]{1,50}") ? code : "provider_declined")
                    .put("message", "Provider reported payment failure");
        }
        if (source.has("failure_reason")) safe.put("failure_reason", "provider_rejected");
        return safe;
    }

    public List<UUID> due() {
        return jdbc.queryForList(
                "SELECT id FROM payments.provider_event WHERE status='RECEIVED' AND next_attempt_at<=now() ORDER BY next_attempt_at,created_at,id LIMIT 20",
                UUID.class);
    }

    @Transactional(timeout = 5)
    public boolean process(UUID id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM payments.provider_event WHERE id=? AND status='RECEIVED' AND next_attempt_at<=now() FOR UPDATE SKIP LOCKED",
                        id);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        try {
            events.processRegistered(
                    id,
                    (String) row.get("provider"),
                    new ProviderWebhookEvent(
                            (String) row.get("provider_event_id"),
                            (String) row.get("event_type"),
                            mapper.readTree((String) row.get("payload")),
                            (String) row.get("payment_ref")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
            throw new IllegalArgumentException("INVALID_STORED_EVENT");
        }
        jdbc.update(
                "UPDATE payments.provider_event SET processed_at=now(),error_code=NULL WHERE id=?",
                id);
        return true;
    }

    @Transactional
    public void failed(UUID id) {
        jdbc.update(
                "UPDATE payments.provider_event SET attempts=attempts+1,status=CASE WHEN attempts+1>=20 THEN 'ATTENTION' ELSE 'RECEIVED' END,error_code='PROCESSING_UNRESOLVED',next_attempt_at=now()+make_interval(secs=>LEAST(300,power(2,LEAST(attempts+1,8)))::integer) WHERE id=? AND status='RECEIVED'",
                id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> attention() {
        return jdbc.queryForList(
                "SELECT id,provider,provider_event_id,event_type,status,attempts,error_code,created_at,next_attempt_at FROM payments.provider_event WHERE status='ATTENTION' ORDER BY created_at,id LIMIT 100");
    }

    @Transactional
    public void replay(UUID id, String actor, String reason) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,status FROM payments.provider_event WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty() || !"ATTENTION".equals(rows.getFirst().get("status")))
            throw new ConflictingEvent();
        if (actor == null
                || actor.length() > 255
                || reason == null
                || reason.isBlank()
                || reason.length() > 500)
            throw new IllegalArgumentException("Replay reason required");
        jdbc.update(
                "INSERT INTO payments.provider_event_replay_audit(event_id,actor,reason) VALUES(?,?,?)",
                id,
                actor,
                reason);
        jdbc.update(
                "UPDATE payments.provider_event SET status='RECEIVED',attempts=0,next_attempt_at=now(),error_code=NULL WHERE id=?",
                id);
    }

    @Transactional
    public void cleanup() {
        // Keep the identity, digest and audit trail. Never delete unresolved financial work.
        jdbc.update(
                "UPDATE payments.provider_event SET payload='{}' WHERE processed_at<now()-interval '90 days' AND status IN('PROCESSED','IGNORED') AND payload<>'{}'");
    }

    public static class ConflictingEvent extends RuntimeException {}
}

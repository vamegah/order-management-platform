package com.example.omp.notification.durable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventRecoveryService {
    private final JdbcTemplate jdbc;
    private final DomainEventIngestion ingestion;
    private final DispatchQueue queue;

    public EventRecoveryService(
            JdbcTemplate jdbc, DomainEventIngestion ingestion, DispatchQueue queue) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.queue = queue;
    }

    public List<Map<String, Object>> quarantine(int limit) {
        return quarantine(limit, 0, false);
    }

    public List<Map<String, Object>> quarantine(int limit, long after, boolean includeReplayed) {
        return jdbc.queryForList(
                "SELECT id,sequence_id AS cursor,consumer_id,source_topic,source_partition,source_offset,payload_hash,failure_code,received_at,replayed_at,replayed_by FROM domain_event_quarantine WHERE sequence_id>? AND (? OR replayed_at IS NULL) ORDER BY sequence_id LIMIT ?",
                after,
                includeReplayed,
                limit);
    }

    public List<Map<String, Object>> failed(int limit) {
        return jdbc.queryForList(
                "SELECT id,transport,destination,message_key,state,attempts,created_at,last_error FROM notification_dispatch WHERE state='FAILED' ORDER BY created_at,id LIMIT ?",
                limit);
    }

    public Map<String, Object> inspect(UUID id) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,consumer_id,source_topic,source_partition,source_offset,payload_hash,failure_code,received_at,replayed_at,replayed_by FROM domain_event_quarantine WHERE id=?",
                        id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    @Transactional
    public void replay(UUID id, String actor, String reason, String corrected) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM domain_event_quarantine WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row = rows.getFirst();
        if (row.get("replayed_at") != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Record already replayed");
        String payload = corrected == null ? (String) row.get("payload") : corrected;
        String consumer = (String) row.get("consumer_id");
        if (corrected != null && ("live".equals(consumer) || "webhook".equals(consumer))) {
            com.example.omp.shared.events.DomainEventEnvelope original = null;
            try {
                original =
                        new com.example.omp.shared.events.DomainEventReader(
                                        com.example.omp.shared.events.EventSerde
                                                .buildObjectMapper())
                                .read((String) row.get("payload"));
            } catch (IllegalArgumentException malformed) {
                /* An operator can repair an unreadable record. */
            }
            if (original != null) {
                var replacement =
                        com.example.omp.shared.events.EventContract.validate(
                                payload, (String) row.get("source_topic"));
                if (!java.util.Objects.equals(original.eventId(), replacement.eventId())
                        || !java.util.Objects.equals(
                                original.aggregateId(), replacement.aggregateId())
                        || !java.util.Objects.equals(original.eventType(), replacement.eventType())
                        || !java.util.Objects.equals(
                                original.data().get("customerId"),
                                replacement.data().get("customerId")))
                    throw new com.example.omp.shared.events.EventContract.InvalidEventException();
            }
        }
        if ("live".equals(consumer) || "webhook".equals(consumer)) {
            ingestion.accept(consumer, payload, (String) row.get("source_topic"));
        } else if ("delivery".equals(consumer) || "delivery-dlq".equals(consumer)) {
            try {
                var message =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(
                                        payload,
                                        com.example.omp.notification.webhook.matcher.DeliveryMessage
                                                .class);
                message.validate();
                queue.enqueue(
                        "quarantine:" + id,
                        "KAFKA",
                        (String) row.get("source_topic"),
                        message.deliveryId().toString(),
                        payload,
                        java.time.Instant.now());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new com.example.omp.shared.events.EventContract.InvalidEventException();
            }
        } else throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown consumer");
        jdbc.update(
                "UPDATE domain_event_quarantine SET replayed_at=now(),replayed_by=? WHERE id=?",
                actor,
                id);
        audit(id, "QUARANTINE_REPLAY", actor, reason, DomainEventIngestion.hash(payload));
    }

    @Transactional
    public void retry(UUID id, String actor, String reason) {
        int changed =
                jdbc.update(
                        "UPDATE notification_dispatch SET state='PENDING',attempts=0,available_at=now(),last_error=NULL WHERE id=? AND state='FAILED'",
                        id);
        if (changed != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dispatch must be failed");
        audit(id, "DISPATCH_RETRY", actor, reason, null);
    }

    private void audit(UUID id, String operation, String actor, String reason, String hash) {
        jdbc.update(
                "INSERT INTO event_recovery_audit(id,resource_id,operation,actor,reason,payload_hash) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),
                id,
                operation,
                actor,
                reason,
                hash);
    }
}

package com.example.omp.notification.durable;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Locked, bounded batches; uncertain broker outcomes deliberately produce redelivery. */
@Component
public class DispatchDrainer {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final boolean enabled;

    public DispatchDrainer(
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            StringRedisTemplate redis,
            MeterRegistry metrics,
            @Value("${omp.events.dispatch-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @PostConstruct
    void metrics() {
        io.micrometer.core.instrument.Gauge.builder(
                        "notification.dispatch.pending",
                        jdbc,
                        j ->
                                j.queryForObject(
                                        "SELECT count(*) FROM notification_dispatch WHERE state <> 'PUBLISHED'",
                                        Long.class))
                .register(metrics);
        io.micrometer.core.instrument.Gauge.builder(
                        "notification.dispatch.oldest.seconds",
                        jdbc,
                        j ->
                                j.queryForObject(
                                        "SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(created_at)),0) FROM notification_dispatch WHERE state <> 'PUBLISHED'",
                                        Double.class))
                .register(metrics);
        io.micrometer.core.instrument.Gauge.builder(
                        "notification.quarantine.pending",
                        jdbc,
                        j ->
                                j.queryForObject(
                                        "SELECT count(*) FROM domain_event_quarantine WHERE replayed_at IS NULL",
                                        Long.class))
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${omp.events.dispatch-delay-ms:500}")
    @Transactional
    public void drain() {
        if (!enabled) return;
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM notification_dispatch WHERE state='PENDING' AND available_at<=now() ORDER BY created_at,id LIMIT 10 FOR UPDATE SKIP LOCKED");
        for (var row : rows) {
            try {
                String destination = (String) row.get("destination"),
                        payload = (String) row.get("payload");
                if ("KAFKA".equals(row.get("transport")))
                    kafka.send(destination, (String) row.get("message_key"), payload)
                            .get(5, TimeUnit.SECONDS);
                else redis.convertAndSend(destination, payload);
                jdbc.update(
                        "UPDATE notification_dispatch SET state='PUBLISHED',published_at=now(),last_error=NULL WHERE id=?",
                        row.get("id"));
                metrics.counter("notification.dispatch.published").increment();
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                jdbc.update(
                        "UPDATE notification_dispatch SET attempts=?,state=?,last_error=?,available_at=? WHERE id=?",
                        attempts,
                        attempts >= 10 ? "FAILED" : "PENDING",
                        "TRANSPORT_FAILURE",
                        Timestamp.from(
                                Instant.now()
                                        .plusSeconds(Math.min(300, 1L << Math.min(attempts, 8)))),
                        row.get("id"));
                metrics.counter("notification.dispatch.failures").increment();
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
    }

    @Scheduled(fixedDelayString = "${omp.events.cleanup-delay-ms:3600000}")
    @Transactional
    public void cleanup() {
        if (!enabled) return;
        jdbc.update(
                "DELETE FROM notification_dispatch WHERE state='PUBLISHED' AND published_at < now()-interval '7 days'");
        // Unresolved work, deduplication identities and audit records are never removed
        // automatically.
        jdbc.update(
                "DELETE FROM domain_event_quarantine WHERE replayed_at < now()-interval '90 days'");
    }
}

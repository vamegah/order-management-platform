package com.example.omp.notification.webhook.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WebhookRetention {
    private final JdbcTemplate jdbc;

    public WebhookRetention(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${webhook.retention.cleanup-delay-ms:3600000}")
    @Transactional
    public void cleanup() {
        jdbc.update(
                "UPDATE webhook_subscription SET previous_secret=NULL,previous_secret_valid_until=NULL WHERE id IN(SELECT id FROM webhook_subscription WHERE previous_secret_valid_until<now() LIMIT 1000)");
        jdbc.update(
                "DELETE FROM webhook_delivery_attempt WHERE id IN(SELECT a.id FROM webhook_delivery_attempt a WHERE completed_at<now()-interval '90 days' AND EXISTS(SELECT 1 FROM webhook_delivery_receipt r WHERE r.delivery_id=a.delivery_id) LIMIT 1000)");
        jdbc.update(
                "DELETE FROM webhook_delivery_dlq WHERE id IN(SELECT d.id FROM webhook_delivery_dlq d WHERE replayed_at<now()-interval '90 days' AND EXISTS(SELECT 1 FROM webhook_delivery_receipt r WHERE r.delivery_id=d.delivery_id) LIMIT 1000)");
        // Receipts and replay audits retain dedup identities. Unresolved DLQs/payloads are
        // retained.
        jdbc.update(
                "DELETE FROM webhook_event_payload WHERE event_id IN(SELECT e.event_id FROM webhook_event_payload e WHERE occurred_at<now()-interval '90 days' AND NOT EXISTS(SELECT 1 FROM webhook_delivery_dlq d WHERE d.event_id=e.event_id) AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempt a WHERE a.event_id=e.event_id) AND EXISTS(SELECT 1 FROM webhook_delivery_receipt r WHERE r.event_id=e.event_id) AND NOT EXISTS(SELECT 1 FROM notification_dispatch q WHERE q.state<>'PUBLISHED' AND q.payload LIKE '%'||e.event_id||'%') LIMIT 1000)");
    }
}

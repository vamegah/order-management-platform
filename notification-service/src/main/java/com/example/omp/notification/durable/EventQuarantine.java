package com.example.omp.notification.durable;

import com.example.omp.shared.events.EventContract;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.backoff.FixedBackOff;

/** A recoverer must commit its database write before Kafka can commit a poison record. */
@Service
public class EventQuarantine {
    private final JdbcTemplate jdbc;

    public EventQuarantine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DefaultErrorHandler handler(String consumer, boolean manual) {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        (record, error) -> capture(consumer, record, error),
                        new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(EventContract.InvalidEventException.class);
        handler.setCommitRecovered(manual);
        handler.setResetStateOnRecoveryFailure(true);
        return handler;
    }

    @Transactional
    public void capture(String consumer, ConsumerRecord<?, ?> record, Exception error) {
        String payload = record.value() == null ? "" : record.value().toString();
        // Broker max-message limits bound retained payloads; API responses never expose them.
        jdbc.update(
                "INSERT INTO domain_event_quarantine(id,consumer_id,source_topic,source_partition,source_offset,payload,payload_hash,failure_code) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(consumer_id,source_topic,source_partition,source_offset) DO NOTHING",
                UUID.randomUUID(),
                consumer,
                record.topic(),
                record.partition(),
                record.offset(),
                payload,
                DomainEventIngestion.hash(payload),
                "PROCESSING_REJECTED");
    }
}

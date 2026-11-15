package com.example.omp.notification.durable;

import com.example.omp.notification.redis.RedisChannels;
import com.example.omp.notification.webhook.matcher.SubscriptionMatcher;
import com.example.omp.shared.events.EventContract;
import com.example.omp.shared.events.EventRegistry;
import com.example.omp.shared.events.EventSerde;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomainEventIngestion {
    private final JdbcTemplate jdbc;
    private final DispatchQueue queue;
    private final SubscriptionMatcher matcher;

    public DomainEventIngestion(
            JdbcTemplate jdbc, DispatchQueue queue, SubscriptionMatcher matcher) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.matcher = matcher;
    }

    @Transactional
    public void accept(String consumer, String payload, String topic) {
        var event = EventContract.validate(payload, topic);
        String hash = hash(payload);
        int inserted =
                jdbc.update(
                        "INSERT INTO domain_event_inbox(consumer_id,event_id,payload_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        consumer,
                        UUID.fromString(event.eventId()),
                        hash);
        if (inserted == 0) {
            String previous =
                    jdbc.queryForObject(
                            "SELECT payload_hash FROM domain_event_inbox WHERE consumer_id=? AND event_id=?",
                            String.class,
                            consumer,
                            UUID.fromString(event.eventId()));
            if (!hash.equals(previous)) throw new EventContract.InvalidEventException();
            return;
        }
        if ("webhook".equals(consumer))
            matcher.matchAndPublish(event.eventId(), event.eventType(), event.data());
        else if ("live".equals(consumer)) {
            String channel =
                    switch (new EventRegistry().categoryOf(event.eventType())) {
                        case ORDER -> RedisChannels.ORDERS;
                        case PAYMENT -> RedisChannels.PAYMENTS;
                        case INVENTORY -> RedisChannels.INVENTORY;
                        default -> throw new EventContract.InvalidEventException();
                    };
            try {
                String normalized =
                        EventSerde.buildObjectMapper()
                                .writeValueAsString(EventContract.typed(event));
                queue.enqueue(
                        event.eventId(),
                        "REDIS",
                        channel,
                        event.aggregateId(),
                        normalized,
                        Instant.now());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new EventContract.InvalidEventException();
            }
        } else throw new IllegalArgumentException("Unknown consumer");
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

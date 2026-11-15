package com.example.omp.notification.durable;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Database handoff; inserts participate in the caller's inbox/business transaction. */
@Component
public class DispatchQueue {
    private final JdbcTemplate jdbc;

    public DispatchQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void enqueue(
            String identity,
            String transport,
            String destination,
            String key,
            String payload,
            Instant due) {
        UUID id =
                UUID.nameUUIDFromBytes(
                        (transport + ":" + destination + ":" + identity)
                                .getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                "INSERT INTO notification_dispatch(id,transport,destination,message_key,payload,available_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                id,
                transport,
                destination,
                key,
                payload,
                Timestamp.from(due));
    }
}

package com.example.omp.testsupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inspects the shared outbox table directly. Used to verify that the outbox writer persisted an
 * event (before asserting the relay published it).
 */
public final class OutboxTestHelper {

    private final JdbcTemplate jdbc;

    public OutboxTestHelper(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<Map<String, Object>> rowsForAggregate(UUID aggregateId) {
        return jdbc.queryForList(
                "SELECT id, event_type, aggregate_id, payload, (status = 'PUBLISHED') AS published, created_at "
                        + "FROM payments.outbox_event WHERE aggregate_id = ? ORDER BY created_at",
                aggregateId.toString());
    }

    public List<Map<String, Object>> unpublishedRows() {
        return jdbc.queryForList(
                "SELECT * FROM payments.outbox_event WHERE status = 'PENDING' ORDER BY created_at");
    }

    public int countByEventType(String eventType) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM payments.outbox_event WHERE event_type = ?",
                        Integer.class,
                        eventType);
        return n == null ? 0 : n;
    }

    public void truncate() {
        jdbc.execute("TRUNCATE TABLE payments.outbox_event");
    }
}

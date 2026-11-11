package com.example.omp.order.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutJournal {
    public record Attempt(UUID orderId, UUID leaseId) {}

    private final JdbcTemplate jdbc;

    public CheckoutJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void start(UUID orderId) {
        jdbc.update(
                "INSERT INTO orders.checkout_attempt (order_id, status) VALUES (?, 'STARTED')",
                orderId);
    }

    /** Locks the same row as recovery; aggregate, event and acceptance commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(UUID orderId) {
        if (jdbc.update(
                        "UPDATE orders.checkout_attempt SET status = 'ACCEPTED', lease_id = NULL, lease_until = NULL WHERE order_id = ? AND status = 'STARTED'",
                        orderId)
                != 1) {
            throw new com.example.omp.order.error.InvalidOrderStateException(
                    "Checkout is fenced by recovery");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void trackAccepted(UUID orderId) {
        jdbc.update(
                "INSERT INTO orders.lifecycle_watch(order_id) VALUES (?) ON CONFLICT DO NOTHING",
                orderId);
    }

    @Transactional
    public void fail(UUID orderId) {
        jdbc.update(
                "UPDATE orders.checkout_attempt SET status = 'FAILED', recover_after = now() WHERE order_id = ? AND status = 'STARTED'",
                orderId);
    }

    @Transactional
    public List<Attempt> claim(int limit) {
        if (limit < 1 || limit > 10)
            throw new IllegalArgumentException("Recovery batch must be 1..10");
        UUID lease = UUID.randomUUID();
        return jdbc.query(
                """
                UPDATE orders.checkout_attempt SET status = 'RECOVERING', lease_id = ?,
                    lease_until = now() + interval '2 minutes', attempts = attempts + 1
                WHERE order_id IN (
                    SELECT order_id FROM orders.checkout_attempt
                    WHERE status <> 'ACCEPTED' AND recover_after <= now()
                    AND (lease_until IS NULL OR lease_until < now())
                    ORDER BY recover_after, order_id LIMIT ? FOR UPDATE SKIP LOCKED
                ) RETURNING order_id, lease_id
                """,
                (rs, row) ->
                        new Attempt(
                                rs.getObject("order_id", UUID.class),
                                rs.getObject("lease_id", UUID.class)),
                lease,
                limit);
    }

    @Transactional
    public void finish(Attempt attempt, boolean success) {
        // Keep rescanning orphan attempts to discover side effects committed after a timed-out
        // lookup.
        jdbc.update(
                """
                UPDATE orders.checkout_attempt SET status = ?, lease_id = NULL, lease_until = NULL,
                    recover_after = now() + interval '5 minutes', last_error = ?
                WHERE order_id = ? AND lease_id = ?
                """,
                success ? "RECOVERED" : "RECOVERING",
                success ? null : "Recovery lookup failed",
                attempt.orderId(),
                attempt.leaseId());
    }
}

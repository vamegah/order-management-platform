package com.example.omp.order.service;

import com.example.omp.order.dto.RecoveryAdoptionRequest;
import com.example.omp.order.dto.RecoveryAdoptionResult;
import com.example.omp.order.error.InvalidOrderDataException;
import com.example.omp.order.error.InvalidOrderStateException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admit a reviewed historical orphan ID; normal discovery still verifies all remote ownership. */
@Service
public class CheckoutRecoveryAdoption {
    private final JdbcTemplate jdbc;

    public CheckoutRecoveryAdoption(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public RecoveryAdoptionResult adopt(
            UUID orderId, RecoveryAdoptionRequest request, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 200)
            throw new AccessDeniedException("A verified operator identity is required");
        if (orderId == null
                || request == null
                || request.reason() == null
                || request.reason().isBlank()
                || request.reason().length() > 500)
            throw new InvalidOrderDataException(
                    "An orphan order identity and bounded reason are required");
        requireMissingOrder(orderId);
        int inserted =
                jdbc.update(
                        """
                INSERT INTO orders.checkout_attempt (order_id, status, recover_after)
                VALUES (?, 'FAILED', now()) ON CONFLICT (order_id) DO NOTHING
                """,
                        orderId);
        // The unique journal identity serializes adoption with normal start/acceptance.
        var current =
                jdbc.query(
                                """
                SELECT attempt.status, audit.actor, audit.reason, audit.requested_at
                FROM orders.checkout_attempt attempt
                LEFT JOIN orders.checkout_recovery_adoption audit ON audit.order_id = attempt.order_id
                WHERE attempt.order_id = ? FOR UPDATE OF attempt
                """,
                                (result, row) ->
                                        new RecoveryAdoptionResult(
                                                orderId,
                                                result.getString("status"),
                                                false,
                                                result.getString("actor"),
                                                result.getString("reason"),
                                                result.getTimestamp("requested_at") == null
                                                        ? null
                                                        : result.getTimestamp("requested_at")
                                                                .toInstant()),
                                orderId)
                        .getFirst();
        if (inserted == 0) {
            if (current.requestedBy() == null || "ACCEPTED".equals(current.status()))
                throw new InvalidOrderStateException(
                        "An existing checkout attempt cannot be adopted");
            return current;
        }
        requireMissingOrder(orderId);
        String reason = request.reason().strip();
        var requestedAt =
                jdbc.queryForObject(
                        """
                INSERT INTO orders.checkout_recovery_adoption (order_id, actor, reason)
                VALUES (?, ?, ?) RETURNING requested_at
                """,
                        java.sql.Timestamp.class,
                        orderId,
                        actor,
                        reason);
        return new RecoveryAdoptionResult(
                orderId, current.status(), true, actor, reason, requestedAt.toInstant());
    }

    private void requireMissingOrder(UUID orderId) {
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM orders.purchase_order WHERE id = ?)",
                        Boolean.class,
                        orderId)))
            throw new InvalidOrderStateException(
                    "An existing order cannot be admitted as an orphan");
    }
}

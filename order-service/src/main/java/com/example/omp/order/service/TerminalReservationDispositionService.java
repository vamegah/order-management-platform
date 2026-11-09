package com.example.omp.order.service;

import com.example.omp.order.dto.TerminalReservationDispositionRequest;
import com.example.omp.order.dto.TerminalReservationDispositionView;
import com.example.omp.order.dto.TerminalReservationDispositionView.Audit;
import com.example.omp.order.dto.TerminalReservationDispositionView.Line;
import com.example.omp.order.error.InvalidOrderDataException;
import com.example.omp.order.error.InvalidOrderStateException;
import com.example.omp.order.error.OrderNotFoundException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator attestation for irrecoverable legacy terminal metadata, never a stock repair. */
@Service
public class TerminalReservationDispositionService {
    private final JdbcTemplate jdbc;

    public TerminalReservationDispositionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, timeout = 5)
    public TerminalReservationDispositionView inspect(UUID orderId) {
        var rows =
                jdbc.query(
                        """
                SELECT o.version, o.status, l.id AS line_id, l.product_sku, l.quantity,
                       l.reservation_id,
                       legacy.line_id IS NOT NULL AND l.reservation_id IS NULL
                           AND o.status IN ('DELIVERED', 'CANCELLED')
                           AND NOT EXISTS (SELECT 1 FROM orders.reservation_reference_repair r
                                           WHERE r.line_id = l.id) AS eligible,
                       audit.id AS audit_id, audit.policy, audit.previous_order_version,
                       audit.actor, audit.reason, audit.evidence, audit.recorded_at
                FROM orders.purchase_order o
                LEFT JOIN orders.order_line l ON l.order_id = o.id
                LEFT JOIN orders.legacy_reservation_reference_line legacy
                    ON legacy.line_id = l.id AND legacy.order_id = o.id
                    AND legacy.product_id = l.product_id AND legacy.sku = l.product_sku
                    AND legacy.quantity = l.quantity
                LEFT JOIN orders.terminal_reservation_reference_disposition audit
                    ON audit.line_id = l.id AND audit.order_id = o.id
                WHERE o.id = ? ORDER BY l.id LIMIT 101
                """,
                        (result, index) -> {
                            UUID lineId = result.getObject("line_id", UUID.class);
                            UUID auditId = result.getObject("audit_id", UUID.class);
                            Audit audit =
                                    auditId == null
                                            ? null
                                            : new Audit(
                                                    auditId,
                                                    result.getString("policy"),
                                                    result.getLong("previous_order_version"),
                                                    result.getString("actor"),
                                                    result.getString("reason"),
                                                    result.getString("evidence"),
                                                    result.getTimestamp("recorded_at").toInstant());
                            return new Row(
                                    result.getLong("version"),
                                    result.getString("status"),
                                    lineId == null
                                            ? null
                                            : new Line(
                                                    lineId,
                                                    result.getString("product_sku"),
                                                    result.getInt("quantity"),
                                                    result.getObject("reservation_id", UUID.class),
                                                    result.getBoolean("eligible"),
                                                    audit));
                        },
                        orderId);
        if (rows.isEmpty()) throw new OrderNotFoundException(orderId);
        if (rows.size() > 100)
            throw new InvalidOrderStateException("Disposition supports at most 100 order lines");
        return new TerminalReservationDispositionView(
                orderId,
                rows.getFirst().version(),
                rows.getFirst().status(),
                0,
                rows.stream().map(Row::line).filter(Objects::nonNull).toList());
    }

    @Transactional(timeout = 5)
    public TerminalReservationDispositionView record(
            UUID orderId, TerminalReservationDispositionRequest request, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 200)
            throw new AccessDeniedException("A verified operator identity is required");
        if (request == null
                || request.expectedVersion() == null
                || request.expectedVersion() < 0
                || invalidText(request.reason(), 500)
                || invalidText(request.evidence(), 2000)
                || request.lineIds() == null
                || request.lineIds().isEmpty()
                || request.lineIds().size() > 100
                || request.lineIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.lineIds()).size() != request.lineIds().size())
            throw new InvalidOrderDataException(
                    "A complete bounded disposition, version, reason and evidence are required");
        if (jdbc.queryForList(
                        "SELECT id FROM orders.purchase_order WHERE id = ? FOR UPDATE",
                        UUID.class,
                        orderId)
                .isEmpty()) throw new OrderNotFoundException(orderId);
        var current = inspect(orderId);
        if (!Set.of("DELIVERED", "CANCELLED").contains(current.status()))
            throw new InvalidOrderStateException(
                    "Only delivered or cancelled historical orders can receive an exception");
        var missing =
                current.lines().stream().filter(line -> line.reservationId() == null).toList();
        if (missing.isEmpty()
                || !new HashSet<>(request.lineIds())
                        .equals(new HashSet<>(missing.stream().map(Line::lineId).toList())))
            throw new InvalidOrderDataException(
                    "Disposition must identify every missing order line exactly once");
        String reason = request.reason().strip(), evidence = request.evidence().strip();
        for (var line : missing) {
            if (!line.eligible())
                throw new InvalidOrderStateException(
                        "A missing line is not eligible for a historical terminal exception");
            var audit = line.audit();
            if (audit != null
                    && (!audit.actor().equals(actor)
                            || !audit.reason().equals(reason)
                            || !audit.evidence().equals(evidence)))
                throw new InvalidOrderStateException(
                        "A disposition already exists; review its original audit");
        }
        int recorded = (int) missing.stream().filter(line -> line.audit() == null).count();
        if (recorded == 0) return current;
        if (current.version() != request.expectedVersion())
            throw new InvalidOrderStateException(
                    "Order changed. Reload before recording a disposition.");
        for (var line : missing) {
            if (line.audit() != null) continue;
            jdbc.update(
                    """
                    INSERT INTO orders.terminal_reservation_reference_disposition
                        (line_id, order_id, id, policy, previous_order_version, actor, reason, evidence)
                    VALUES (?, ?, ?, 'IRRECOVERABLE_LEGACY_REFERENCE', ?, ?, ?, ?)
                    """,
                    line.lineId(),
                    orderId,
                    UUID.randomUUID(),
                    current.version(),
                    actor,
                    reason,
                    evidence);
        }
        if (jdbc.update(
                        """
                UPDATE orders.purchase_order SET version = version + 1, updated_at = now()
                WHERE id = ? AND version = ?
                """,
                        orderId,
                        request.expectedVersion())
                != 1) throw new InvalidOrderStateException("Order changed during disposition");
        var updated = inspect(orderId);
        return new TerminalReservationDispositionView(
                orderId, updated.version(), updated.status(), recorded, updated.lines());
    }

    private static boolean invalidText(String text, int maximum) {
        return text == null || text.isBlank() || text.length() > maximum;
    }

    private record Row(long version, String status, Line line) {}
}

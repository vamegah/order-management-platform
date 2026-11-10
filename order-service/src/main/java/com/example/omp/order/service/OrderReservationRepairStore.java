package com.example.omp.order.service;

import com.example.omp.order.dto.ReservationReferenceRepairRequest;
import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.dto.ReservationReferenceRepairView.Line;
import com.example.omp.order.error.InvalidOrderDataException;
import com.example.omp.order.error.InvalidOrderStateException;
import com.example.omp.order.error.OrderNotFoundException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short local transactions; remote verification belongs to the calling service. */
@Service
public class OrderReservationRepairStore {
    private final JdbcTemplate jdbc;

    public OrderReservationRepairStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReservationReferenceRepairView inspect(UUID orderId) {
        return jdbc.query(
                """
                SELECT o.version, o.status, l.id AS line_id, l.product_sku, l.quantity, l.reservation_id, l.reservation_warehouse_id
                FROM orders.purchase_order o
                LEFT JOIN orders.order_line l ON l.order_id = o.id
                WHERE o.id = ? ORDER BY l.created_at, l.id
                """,
                result -> {
                    if (!result.next()) throw new OrderNotFoundException(orderId);
                    long version = result.getLong("version");
                    String status = result.getString("status");
                    var lines = new ArrayList<Line>();
                    do {
                        UUID lineId = result.getObject("line_id", UUID.class);
                        if (lineId != null)
                            lines.add(
                                    new Line(
                                            lineId,
                                            result.getString("product_sku"),
                                            result.getInt("quantity"),
                                            result.getObject("reservation_id", UUID.class),
                                            result.getString("reservation_warehouse_id")));
                    } while (result.next());
                    return view(orderId, version, status, 0, lines);
                },
                orderId);
    }

    @Transactional
    public ReservationReferenceRepairView restore(
            UUID orderId,
            long expectedVersion,
            Map<UUID, UUID> bindings,
            String actor,
            String reason) {
        var locked =
                jdbc.query(
                        "SELECT id FROM orders.purchase_order WHERE id = ? FOR UPDATE",
                        (result, row) -> result.getObject(1, UUID.class),
                        orderId);
        if (locked.isEmpty()) throw new OrderNotFoundException(orderId);
        var current = inspect(orderId);
        int missing = verifyPlan(current, bindings);
        if (missing == 0) return current;
        requireVersion(current, expectedVersion);
        for (var line : current.lines()) {
            if (line.reservationId() != null) continue;
            UUID reservationId = bindings.get(line.lineId());
            int changed =
                    jdbc.update(
                            """
                    UPDATE orders.order_line SET reservation_id = ?
                    WHERE id = ? AND order_id = ? AND reservation_id IS NULL
                    """,
                            reservationId,
                            line.lineId(),
                            orderId);
            if (changed != 1)
                throw new InvalidOrderStateException("Order changed during reference repair");
            jdbc.update(
                    """
                    INSERT INTO orders.reservation_reference_repair
                        (id, order_id, line_id, reservation_id, previous_order_version, actor, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    orderId,
                    line.lineId(),
                    reservationId,
                    current.version(),
                    actor,
                    reason);
        }
        int changed =
                jdbc.update(
                        """
                UPDATE orders.purchase_order SET version = version + 1, updated_at = now()
                WHERE id = ? AND version = ?
                """,
                        orderId,
                        expectedVersion);
        if (changed != 1)
            throw new InvalidOrderStateException("Order changed during reference repair");
        var updated = inspect(orderId);
        return new ReservationReferenceRepairView(
                updated.orderId(),
                updated.version(),
                updated.status(),
                updated.missing(),
                missing,
                updated.lines());
    }

    static Map<UUID, UUID> bindings(ReservationReferenceRepairRequest request) {
        if (request == null
                || request.expectedVersion() == null
                || request.expectedVersion() < 0
                || request.reason() == null
                || request.reason().isBlank()
                || request.reason().length() > 500
                || request.bindings() == null
                || request.bindings().isEmpty()
                || request.bindings().size() > 100)
            throw new InvalidOrderDataException(
                    "A bounded reference repair, version and reason are required");
        var bindings = new LinkedHashMap<UUID, UUID>();
        var reservations = new HashSet<UUID>();
        for (var binding : request.bindings()) {
            if (binding == null
                    || binding.lineId() == null
                    || binding.reservationId() == null
                    || bindings.putIfAbsent(binding.lineId(), binding.reservationId()) != null
                    || !reservations.add(binding.reservationId()))
                throw new InvalidOrderDataException(
                        "Every line requires a distinct reservation reference");
        }
        return Collections.unmodifiableMap(bindings);
    }

    static int verifyPlan(ReservationReferenceRepairView order, Map<UUID, UUID> bindings) {
        if (bindings == null
                || bindings.values().contains(null)
                || new HashSet<>(bindings.values()).size() != bindings.size()
                || order.lines().isEmpty()
                || order.lines().size() > 100
                || bindings.size() != order.lines().size()
                || !bindings.keySet()
                        .equals(new HashSet<>(order.lines().stream().map(Line::lineId).toList())))
            throw new InvalidOrderDataException(
                    "Repair must identify every order line exactly once");
        int missing = 0;
        for (var line : order.lines()) {
            if (line.reservationId() == null) missing++;
            else if (!line.reservationId().equals(bindings.get(line.lineId())))
                throw new InvalidOrderStateException(
                        "An existing reservation reference cannot be replaced");
        }
        if (missing > 0 && !Set.of("DRAFT", "PLACED", "CONFIRMED").contains(order.status()))
            throw new InvalidOrderStateException(
                    "References can only be restored before fulfillment or cancellation");
        return missing;
    }

    static void requireVersion(ReservationReferenceRepairView order, long expectedVersion) {
        if (order.version() != expectedVersion)
            throw new InvalidOrderStateException(
                    "Order changed. Reload before repairing references.");
    }

    private static ReservationReferenceRepairView view(
            UUID orderId, long version, String status, int restored, List<Line> lines) {
        return new ReservationReferenceRepairView(
                orderId,
                version,
                status,
                (int) lines.stream().filter(line -> line.reservationId() == null).count(),
                restored,
                List.copyOf(lines));
    }
}

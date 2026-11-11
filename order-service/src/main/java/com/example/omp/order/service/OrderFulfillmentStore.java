package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderStatus;
import com.example.omp.order.events.OrderEventPublisher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions only; all inventory RPCs belong to the queue outside this bean. */
@Service
public class OrderFulfillmentStore {
    public record Task(
            UUID id,
            UUID orderId,
            UUID reservationId,
            String sku,
            int quantity,
            UUID leaseId,
            String warehouseId) {
        public Task(
                UUID id, UUID orderId, UUID reservationId, String sku, int quantity, UUID leaseId) {
            this(id, orderId, reservationId, sku, quantity, leaseId, null);
        }
    }

    private record Request(String requestedBy, String reason) {}

    private final JdbcTemplate jdbc;
    private final OrderTransactionService orders;
    private final OrderEventPublisher events;

    public OrderFulfillmentStore(
            JdbcTemplate jdbc, OrderTransactionService orders, OrderEventPublisher events) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order, String by, String reason) {
        if (order.getStatus() != OrderStatus.FULFILLING
                || order.getLines().isEmpty()
                || order.getLines().size() > 100)
            throw new IllegalStateException("Invalid shipment intent");
        if (by == null
                || by.isBlank()
                || by.length() > 100
                || (reason != null && reason.length() > 500))
            throw new com.example.omp.order.error.InvalidOrderDataException(
                    "Invalid shipment audit metadata");
        jdbc.update(
                "INSERT INTO orders.fulfillment_request (order_id, requested_by, reason) VALUES (?, ?, ?)",
                order.getId(),
                by,
                reason);
        for (var line : order.getLines()) {
            if (line.getReservationId() == null)
                throw new IllegalStateException("Missing shipment reservation");
            jdbc.update(
                    "INSERT INTO orders.fulfillment_task (id, order_id, reservation_id, sku, quantity, warehouse_id) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    order.getId(),
                    line.getReservationId(),
                    line.getProductSku(),
                    line.getQuantity(),
                    line.getReservationWarehouseId());
        }
    }

    @Transactional
    public boolean startAttempt(UUID orderId) {
        // Move attempted requests back in the due ordering so failed orders cannot starve others.
        return jdbc.update(
                        """
                UPDATE orders.fulfillment_request request SET attempts = attempts + 1,
                    next_attempt_at = now() + interval '1 minute'
                WHERE request.order_id = ? AND request.completed_at IS NULL
                AND EXISTS (SELECT 1 FROM orders.purchase_order parent_order
                    WHERE parent_order.id = request.order_id AND parent_order.status = 'FULFILLING')
                """,
                        orderId)
                == 1;
    }

    @Transactional(readOnly = true)
    public List<UUID> dueOrders() {
        return jdbc.query(
                """
                SELECT request.order_id FROM orders.fulfillment_request request
                JOIN orders.purchase_order parent_order ON parent_order.id = request.order_id
                WHERE request.completed_at IS NULL AND request.next_attempt_at <= now()
                    AND parent_order.status = 'FULFILLING'
                ORDER BY request.next_attempt_at, request.order_id LIMIT 10
                """,
                (rs, row) -> rs.getObject("order_id", UUID.class));
    }

    @Transactional
    public Optional<Task> claimTask(UUID orderId) {
        UUID lease = UUID.randomUUID();
        var tasks =
                jdbc.query(
                        """
                UPDATE orders.fulfillment_task SET lease_id = ?,
                    lease_until = now() + interval '2 minutes', attempts = attempts + 1
                WHERE id = (
                    SELECT task.id FROM orders.fulfillment_task task
                    JOIN orders.purchase_order parent_order ON parent_order.id = task.order_id
                    WHERE task.order_id = ? AND parent_order.status = 'FULFILLING'
                        AND NOT task.completed AND task.next_attempt_at <= now()
                        AND (task.lease_until IS NULL OR task.lease_until < now())
                    ORDER BY task.created_at, task.id LIMIT 1 FOR UPDATE OF task SKIP LOCKED
                ) RETURNING id, order_id, reservation_id, sku, quantity, lease_id, warehouse_id
                """,
                        (rs, row) ->
                                new Task(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("order_id", UUID.class),
                                        rs.getObject("reservation_id", UUID.class),
                                        rs.getString("sku"),
                                        rs.getInt("quantity"),
                                        rs.getObject("lease_id", UUID.class),
                                        rs.getString("warehouse_id")),
                        lease,
                        orderId);
        return tasks.stream().findFirst();
    }

    @Transactional
    public boolean completeTask(Task task) {
        return jdbc.update(
                        """
                UPDATE orders.fulfillment_task SET completed = true, completed_at = now(),
                    lease_id = NULL, lease_until = NULL, last_error = NULL
                WHERE id = ? AND lease_id = ? AND NOT completed AND lease_until > clock_timestamp()
                """,
                        task.id(),
                        task.leaseId())
                == 1;
    }

    @Transactional
    public void failTask(Task task, RuntimeException failure) {
        jdbc.update(
                """
                UPDATE orders.fulfillment_task SET lease_id = NULL, lease_until = NULL,
                    next_attempt_at = now() + interval '1 minute', last_error = ?
                WHERE id = ? AND lease_id = ? AND NOT completed
                """,
                failure.getClass().getSimpleName(),
                task.id(),
                task.leaseId());
    }

    @Transactional(readOnly = true)
    public boolean readyForCompletion(UUID orderId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT EXISTS (SELECT 1 FROM orders.fulfillment_task WHERE order_id = ?)
                    AND NOT EXISTS (SELECT 1 FROM orders.fulfillment_task WHERE order_id = ? AND NOT completed)
                """,
                        Boolean.class,
                        orderId,
                        orderId));
    }

    @Transactional
    public boolean finish(UUID orderId, PaymentIntentDto payment) {
        if (payment == null || !"SHIPPED".equals(payment.fulfillmentState()))
            throw new IllegalStateException("Shipment requires the durable payment decision");
        // Serialize finalizers; aggregate, history, outbox and request completion commit together.
        var requests =
                jdbc.query(
                        """
                SELECT requested_by, reason FROM orders.fulfillment_request
                WHERE order_id = ? AND completed_at IS NULL FOR UPDATE
                """,
                        (rs, row) ->
                                new Request(rs.getString("requested_by"), rs.getString("reason")),
                        orderId);
        if (requests.isEmpty()) return false;
        var counts =
                jdbc.queryForMap(
                        """
                SELECT count(*) AS total, count(*) FILTER (WHERE NOT completed) AS pending
                FROM orders.fulfillment_task WHERE order_id = ?
                """,
                        orderId);
        int total = ((Number) counts.get("total")).intValue();
        if (total == 0 || ((Number) counts.get("pending")).intValue() != 0) return false;
        var request = requests.getFirst();
        orders.findAndApply(
                orderId,
                order -> {
                    if ((order.getStatus() != OrderStatus.FULFILLING
                                    && order.getStatus() != OrderStatus.CANCELLING)
                            || order.getLines().size() != total)
                        throw new IllegalStateException(
                                "Shipment no longer matches its durable intent");
                    OrderPaymentPolicy.requireMatchingPayment(order, payment);
                    order.ship(request.requestedBy(), request.reason());
                    events.publishShipped(order, request.requestedBy(), request.reason());
                });
        jdbc.update(
                "UPDATE orders.fulfillment_request SET completed_at = now(), last_error = NULL WHERE order_id = ?",
                orderId);
        return true;
    }

    @Transactional
    public void requestAbort(UUID orderId, String reason) {
        orders.findAndApply(
                orderId,
                order -> {
                    if (order.getStatus() == OrderStatus.FULFILLING)
                        order.cancel("system:lifecycle", reason);
                });
        jdbc.update(
                """
            INSERT INTO orders.lifecycle_watch(order_id, reason) VALUES (?, ?)
            ON CONFLICT (order_id) DO UPDATE SET next_scan_at = clock_timestamp(), reason = EXCLUDED.reason
            """,
                orderId,
                reason);
    }

    @Transactional
    public void recordFailure(UUID orderId, RuntimeException failure) {
        jdbc.update(
                "UPDATE orders.fulfillment_request SET last_error = ? WHERE order_id = ? AND completed_at IS NULL",
                failure.getClass().getSimpleName(),
                orderId);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orders.fulfillment_request WHERE completed_at IS NULL",
                Long.class);
    }
}

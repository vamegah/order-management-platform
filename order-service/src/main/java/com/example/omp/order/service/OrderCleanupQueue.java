package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, leased cleanup work. RPCs run outside database transactions using caller authentication.
 */
@Service
public class OrderCleanupQueue {
    public record Task(UUID id, UUID orderId, String resourceType, UUID resourceId, int attempts) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final MeterRegistry metrics;

    public OrderCleanupQueue(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            InventoryClient inventory,
            PaymentClient payments,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.inventory = inventory;
        this.payments = payments;
        this.metrics = metrics;
        metrics.gauge(
                "order.cleanup.pending",
                this,
                queue ->
                        queue.jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE NOT completed",
                                Long.class));
    }

    /**
     * Joins the order cancellation transaction; compensation callers get a short new transaction.
     */
    @Transactional
    public void enqueue(UUID orderId, String type, UUID resourceId) {
        if (!List.of("RESERVATION", "PAYMENT").contains(type))
            throw new IllegalArgumentException("Invalid cleanup type");
        jdbc.update(
                "INSERT INTO orders.cleanup_task (id, order_id, resource_type, resource_id) VALUES (?, ?, ?, ?) ON CONFLICT (resource_type, resource_id) DO NOTHING",
                UUID.randomUUID(),
                orderId,
                type,
                resourceId);
    }

    public int retryDue() {
        var orders =
                jdbc.query(
                        """
                SELECT task.order_id FROM orders.cleanup_task task
                WHERE NOT completed AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now())
                AND NOT EXISTS (SELECT 1 FROM orders.purchase_order existing_order
                    WHERE existing_order.id = task.order_id AND existing_order.status <> 'CANCELLED')
                GROUP BY task.order_id ORDER BY min(next_attempt_at), task.order_id LIMIT 10
                """,
                        (rs, row) -> rs.getObject("order_id", UUID.class));
        int completed = 0;
        for (UUID orderId : orders) completed += retryOrder(orderId, 10);
        return completed;
    }

    public int retryOrder(UUID orderId, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Cleanup limit must be 1..100");
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("Cleanup RPCs require no ambient transaction");
        if (Thread.currentThread().isInterrupted()) return 0;
        UUID lease = UUID.randomUUID();
        List<Task> tasks =
                transactions.execute(
                        status ->
                                jdbc.query(
                                        """
                UPDATE orders.cleanup_task SET lease_id = ?, lease_until = now() + interval '2 minutes', attempts = attempts + 1
                WHERE id IN (
                    SELECT task.id FROM orders.cleanup_task task
                    WHERE NOT completed AND next_attempt_at <= now()
                    AND (lease_until IS NULL OR lease_until < now())
                    AND order_id = ?
                    AND NOT EXISTS (
                        SELECT 1 FROM orders.purchase_order existing_order
                        WHERE existing_order.id = task.order_id AND existing_order.status <> 'CANCELLED'
                    )
                    ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                ) RETURNING id, order_id, resource_type, resource_id, attempts
                """,
                                        (rs, row) ->
                                                new Task(
                                                        rs.getObject("id", UUID.class),
                                                        rs.getObject("order_id", UUID.class),
                                                        rs.getString("resource_type"),
                                                        rs.getObject("resource_id", UUID.class),
                                                        rs.getInt("attempts")),
                                        lease,
                                        orderId,
                                        limit));
        int completed = 0;
        for (Task task : tasks == null ? List.<Task>of() : tasks) {
            try {
                if (task.resourceType().equals("RESERVATION")) cleanupReservation(task);
                else if (task.resourceType().equals("PAYMENT")) cleanupPayment(task);
                else throw new IllegalStateException("Unknown cleanup type");
                int updated =
                        jdbc.update(
                                "UPDATE orders.cleanup_task SET completed = true, completed_at = now(), lease_id = NULL, lease_until = NULL, last_error = NULL WHERE id = ? AND lease_id = ?",
                                task.id(),
                                lease);
                completed += updated;
                metrics.counter("order.cleanup.completed", "type", task.resourceType())
                        .increment(updated);
            } catch (RuntimeException failure) {
                jdbc.update(
                        "UPDATE orders.cleanup_task SET lease_id = NULL, lease_until = NULL, next_attempt_at = now() + interval '1 minute', last_error = ? WHERE id = ? AND lease_id = ?",
                        failure.getClass().getSimpleName(),
                        task.id(),
                        lease);
                metrics.counter("order.cleanup.failures", "type", task.resourceType()).increment();
                LoggerFactory.getLogger(getClass())
                        .warn(
                                "Order cleanup pending: task={} order={} type={}",
                                task.id(),
                                task.orderId(),
                                task.resourceType());
            }
        }
        return completed;
    }

    private void cleanupReservation(Task task) {
        ReservationDto reservation = inventory.get(task.resourceId());
        validateReservation(task, reservation);
        if (isReleased(reservation)) return;
        if (!"RESERVATION_STATUS_ACTIVE".equals(reservation.status()))
            throw new IllegalStateException("Reservation cleanup state is unsupported");

        ReservationDto released = inventory.release(task.resourceId().toString());
        validateReservation(task, released);
        if (!isReleased(released))
            throw new IllegalStateException("Reservation cleanup remains unresolved");
    }

    private static void validateReservation(Task task, ReservationDto reservation) {
        if (reservation == null
                || !task.resourceId().equals(reservation.id())
                || !task.orderId().equals(reservation.orderId()))
            throw new IllegalStateException("Cleanup reservation identity mismatch");
    }

    private static boolean isReleased(ReservationDto reservation) {
        return "RESERVATION_STATUS_RELEASED".equals(reservation.status())
                || "RESERVATION_STATUS_EXPIRED".equals(reservation.status());
    }

    private void cleanupPayment(Task task) {
        PaymentIntentDto payment = payments.get(task.resourceId());
        validatePayment(task, payment);
        switch (payment.status()) {
            case "PAYMENT_STATUS_CANCELLED",
                    "PAYMENT_STATUS_FAILED",
                    "PAYMENT_STATUS_REFUNDED" -> {}
            case "PAYMENT_STATUS_PENDING" -> {
                PaymentIntentDto cancelled =
                        payments.cancel(task.resourceId().toString(), "Order cleanup");
                validatePayment(task, cancelled);
                requireTerminal(cancelled);
            }
            case "PAYMENT_STATUS_CONFIRMED", "PAYMENT_STATUS_PARTIALLY_REFUNDED" -> {
                var refund =
                        payments.refundRemaining(task.resourceId(), "order-cleanup-" + task.id());
                if (refund == null
                        || refund.id() == null
                        || !task.resourceId().equals(refund.paymentId())
                        || !"REFUND_STATUS_COMPLETED".equals(refund.status()))
                    throw new IllegalStateException("Refund remains unresolved");
                PaymentIntentDto refunded = payments.get(task.resourceId());
                validatePayment(task, refunded);
                requireTerminal(refunded);
            }
            default -> throw new IllegalStateException("Payment cleanup state is unsupported");
        }
    }

    private static void validatePayment(Task task, PaymentIntentDto payment) {
        if (payment == null
                || !task.resourceId().equals(payment.id())
                || !task.orderId().equals(payment.orderId()))
            throw new IllegalStateException("Cleanup payment identity mismatch");
    }

    private static void requireTerminal(PaymentIntentDto payment) {
        if (!List.of("PAYMENT_STATUS_CANCELLED", "PAYMENT_STATUS_FAILED", "PAYMENT_STATUS_REFUNDED")
                .contains(payment.status()))
            throw new IllegalStateException("Payment cleanup remains unresolved");
    }
}

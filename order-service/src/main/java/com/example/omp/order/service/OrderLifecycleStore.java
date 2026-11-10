package com.example.omp.order.service;

import com.example.omp.order.domain.OrderStatus;
import com.example.omp.order.error.InvalidOrderDataException;
import com.example.omp.order.error.OrderNotFoundException;
import com.example.omp.order.events.OrderEventPublisher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leases and admissions use database time. No transaction in this bean performs an RPC. */
@Service
public class OrderLifecycleStore {
    public record Claim(UUID orderId, UUID leaseId) {}

    private final JdbcTemplate jdbc;
    private final OrderTransactionService orders;
    private final OrderCleanupQueue cleanup;
    private final OrderEventPublisher events;

    public OrderLifecycleStore(
            JdbcTemplate jdbc,
            OrderTransactionService orders,
            OrderCleanupQueue cleanup,
            OrderEventPublisher events) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.cleanup = cleanup;
        this.events = events;
    }

    @Transactional
    public List<Claim> claim(int limit) {
        if (limit < 1 || limit > 10)
            throw new IllegalArgumentException("Lifecycle batch must be 1..10");
        UUID lease = UUID.randomUUID();
        return jdbc.query(
                """
            UPDATE orders.lifecycle_watch SET lease_id = ?,
                lease_until = clock_timestamp() + interval '2 minutes', attempts = attempts + 1
            WHERE order_id IN (
                SELECT w.order_id FROM orders.lifecycle_watch w
                JOIN orders.purchase_order o ON o.id = w.order_id
                WHERE (o.status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','CANCELLING','CANCELLED')
                    OR EXISTS (SELECT 1 FROM orders.legacy_lifecycle_resolution legacy
                        WHERE legacy.order_id = o.id AND legacy.resolved_at IS NULL))
                  AND w.next_scan_at <= clock_timestamp()
                  AND (w.lease_until IS NULL OR w.lease_until < clock_timestamp())
                ORDER BY w.next_scan_at, w.order_id LIMIT ? FOR UPDATE OF w SKIP LOCKED
            ) RETURNING order_id, lease_id
            """,
                (rs, row) ->
                        new Claim(
                                rs.getObject("order_id", UUID.class),
                                rs.getObject("lease_id", UUID.class)),
                lease,
                limit);
    }

    @Transactional
    public List<Claim> claimOrder(UUID orderId) {
        return jdbc.query(
                """
            UPDATE orders.lifecycle_watch SET lease_id = ?,
                lease_until = clock_timestamp() + interval '2 minutes', attempts = attempts + 1
            WHERE order_id = ? AND next_scan_at <= clock_timestamp()
                AND (lease_until IS NULL OR lease_until < clock_timestamp())
            RETURNING order_id, lease_id
            """,
                (rs, row) ->
                        new Claim(
                                rs.getObject("order_id", UUID.class),
                                rs.getObject("lease_id", UUID.class)),
                UUID.randomUUID(),
                orderId);
    }

    @Transactional
    public void requestCancellation(Claim claim, String reason) {
        fence(claim);
        orders.findAndApply(
                claim.orderId(),
                order -> {
                    if (order.getStatus() == OrderStatus.CANCELLED
                            || order.getStatus() == OrderStatus.CANCELLING) return;
                    order.cancel("system:lifecycle", reason);
                    if (order.getStatus() == OrderStatus.CANCELLED)
                        events.publishCancelled(order, "system:lifecycle", reason);
                    cleanup.trackCancellation(order.getId(), "system:lifecycle", reason);
                });
    }

    /** Admit both fully verified result sets and acknowledge local compensation atomically. */
    @Transactional(timeout = 10)
    public void admitCompensation(Claim claim, List<UUID> reservationIds, List<UUID> paymentIds) {
        fence(claim);
        requireIds(reservationIds);
        requireIds(paymentIds);
        orders.findAndApply(
                claim.orderId(),
                order -> {
                    if (order.getStatus() != OrderStatus.CANCELLED
                            && order.getStatus() != OrderStatus.CANCELLING)
                        throw new IllegalStateException("Order is no longer being cancelled");
                    if (order.getStatus() == OrderStatus.CANCELLING) {
                        var audit =
                                jdbc.queryForMap(
                                        "SELECT requested_by, reason FROM orders.lifecycle_watch WHERE order_id = ?",
                                        order.getId());
                        String actor = (String) audit.get("requested_by"),
                                reason = (String) audit.get("reason");
                        order.cancel(actor, reason);
                        events.publishCancelled(order, actor, reason);
                        jdbc.update(
                                "UPDATE orders.fulfillment_request SET completed_at = clock_timestamp(), last_error = 'Compensated' WHERE order_id = ? AND completed_at IS NULL",
                                order.getId());
                    }
                    for (UUID id : reservationIds)
                        cleanup.enqueue(order.getId(), "RESERVATION", id);
                    for (UUID id : paymentIds) cleanup.enqueue(order.getId(), "PAYMENT", id);
                });
        jdbc.update(
                "UPDATE orders.lifecycle_watch SET compensation_ready = true WHERE order_id = ?",
                claim.orderId());
        if (!live(claim))
            throw new IllegalStateException("Lifecycle lease expired during admission");
    }

    @Transactional
    public boolean finish(Claim claim, boolean cancelled) {
        long pending =
                jdbc.queryForObject(
                        "SELECT count(*) FROM orders.cleanup_task WHERE order_id = ? AND NOT completed",
                        Long.class,
                        claim.orderId());
        int seconds = cancelled && pending == 0 ? 300 : cancelled ? 60 : 30;
        return jdbc.update(
                        """
            UPDATE orders.lifecycle_watch SET lease_id = NULL, lease_until = NULL,
                next_scan_at = clock_timestamp() + (? * interval '1 second'),
                last_success_at = clock_timestamp(), attempts = 0, attention = false, last_error = NULL
            WHERE order_id = ? AND lease_id = ? AND lease_until > clock_timestamp()
            """,
                        seconds,
                        claim.orderId(),
                        claim.leaseId())
                == 1;
    }

    @Transactional
    public void fail(Claim claim, RuntimeException failure) {
        jdbc.update(
                """
            UPDATE orders.lifecycle_watch SET lease_id = NULL, lease_until = NULL,
                next_scan_at = clock_timestamp() + interval '1 minute',
                last_error = ?, attention = attempts >= 3
            WHERE order_id = ? AND lease_id = ? AND lease_until > clock_timestamp()
            """,
                failure.getClass().getSimpleName(),
                claim.orderId(),
                claim.leaseId());
    }

    @Transactional(readOnly = true)
    public boolean legacyPending(UUID orderId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM orders.legacy_lifecycle_resolution WHERE order_id = ? AND resolved_at IS NULL AND resolution IS NULL)",
                        Boolean.class,
                        orderId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> inspect(UUID orderId) {
        var rows =
                jdbc.queryForList(
                        """
            SELECT w.order_id, o.status, w.attempts, w.attention, w.last_error, w.next_scan_at,
                w.lease_until, w.last_success_at,
                (SELECT count(*) FROM orders.cleanup_task t WHERE t.order_id = w.order_id AND NOT t.completed) AS pending_tasks
            FROM orders.lifecycle_watch w JOIN orders.purchase_order o ON o.id = w.order_id
            WHERE w.order_id = ?
            """,
                        orderId);
        if (rows.isEmpty()) throw new OrderNotFoundException(orderId);
        return rows.getFirst();
    }

    @Transactional
    public void retry(UUID orderId, String actor, String reason) {
        if (actor == null
                || actor.isBlank()
                || actor.length() > 100
                || reason == null
                || reason.isBlank()
                || reason.length() > 500)
            throw new InvalidOrderDataException(
                    "A verified actor and bounded retry reason are required");
        if (jdbc.update(
                        "UPDATE orders.lifecycle_watch SET next_scan_at = clock_timestamp() WHERE order_id = ?",
                        orderId)
                != 1) throw new OrderNotFoundException(orderId);
        jdbc.update(
                "INSERT INTO orders.lifecycle_retry_audit(id, order_id, actor, reason) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                orderId,
                actor,
                reason.strip());
    }

    @Transactional(readOnly = true)
    public long attentionCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orders.lifecycle_watch WHERE attention", Long.class);
    }

    @Transactional(readOnly = true)
    public double lagSeconds() {
        return jdbc.queryForObject(
                """
            SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM clock_timestamp() - min(w.next_scan_at)),0),0)
            FROM orders.lifecycle_watch w JOIN orders.purchase_order o ON o.id = w.order_id
            WHERE o.status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','CANCELLING','CANCELLED')
            """,
                Double.class);
    }

    private void fence(Claim claim) {
        if (jdbc.queryForList(
                        """
            SELECT order_id FROM orders.lifecycle_watch
            WHERE order_id = ? AND lease_id = ? AND lease_until > clock_timestamp() FOR UPDATE
            """,
                        UUID.class,
                        claim.orderId(),
                        claim.leaseId())
                .isEmpty()) throw new IllegalStateException("Lifecycle lease is no longer current");
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireCurrent(Claim claim) {
        fence(claim);
    }

    private boolean live(Claim claim) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT lease_until > clock_timestamp() FROM orders.lifecycle_watch WHERE order_id = ? AND lease_id = ?",
                        Boolean.class,
                        claim.orderId(),
                        claim.leaseId()));
    }

    private static void requireIds(List<UUID> ids) {
        if (ids == null
                || ids.size() > 100
                || ids.stream().anyMatch(java.util.Objects::isNull)
                || ids.stream().distinct().count() != ids.size())
            throw new IllegalArgumentException("Invalid complete cleanup plan");
    }
}

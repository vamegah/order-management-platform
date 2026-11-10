package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderStatus;
import com.example.omp.order.events.OrderEventPublisher;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies only to immutable identities captured at upgrade, after remote verification. */
@Service
public class OrderLegacyLifecycleStore {
    private static final String ACTOR = "system:legacy-lifecycle";
    private final JdbcTemplate jdbc;
    private final OrderLifecycleStore leases;
    private final OrderTransactionService orders;
    private final OrderCleanupQueue cleanup;
    private final OrderEventPublisher events;
    @jakarta.persistence.PersistenceContext private EntityManager entities;

    public OrderLegacyLifecycleStore(
            JdbcTemplate jdbc,
            OrderLifecycleStore leases,
            OrderTransactionService orders,
            OrderCleanupQueue cleanup,
            OrderEventPublisher events) {
        this.jdbc = jdbc;
        this.leases = leases;
        this.orders = orders;
        this.cleanup = cleanup;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public void resolve(
            OrderLifecycleStore.Claim claim,
            Order snapshot,
            Map<UUID, ReservationDto> verified,
            boolean irrecoverable) {
        leases.requireCurrent(claim);
        var legacy =
                jdbc.queryForList(
                        "SELECT order_id FROM orders.legacy_lifecycle_resolution WHERE order_id = ? AND resolved_at IS NULL FOR UPDATE",
                        UUID.class,
                        snapshot.getId());
        if (legacy.isEmpty()) return;
        var order = orders.findByIdFull(snapshot.getId());
        if (order.getVersion() != snapshot.getVersion())
            throw new IllegalStateException("Legacy order changed during verification");
        long captured =
                jdbc.queryForObject(
                        """
            SELECT count(*) FROM orders.legacy_lifecycle_line legacy JOIN orders.order_line l
                ON l.id = legacy.line_id AND l.order_id = legacy.order_id
                AND l.product_id = legacy.product_id AND l.product_sku = legacy.sku
                AND l.quantity = legacy.quantity
                AND l.reservation_warehouse_id IS NOT DISTINCT FROM legacy.original_warehouse
            WHERE legacy.order_id = ?
            """,
                        Long.class,
                        order.getId());
        if (captured != order.getLines().size() || order.getLines().size() > 100)
            throw new IllegalStateException("Legacy identities changed; operator repair required");
        if (irrecoverable && order.getStatus() == OrderStatus.SHIPPED)
            throw new IllegalStateException(
                    "A physical shipment requires operator ledger reconciliation");
        String reason =
                irrecoverable
                        ? "Approved upgrade: irrecoverable historical reservation references"
                        : "Upgrade verified original reservation ownership";
        for (var line : order.getLines()) {
            ReservationDto proof = verified.get(line.getId());
            if (!irrecoverable && proof == null)
                throw new IllegalArgumentException(
                        "Every active line requires original reservation proof");
            UUID after = proof == null ? null : proof.id();
            jdbc.update(
                    """
                INSERT INTO orders.lifecycle_reference_audit(id, order_id, line_id,
                    previous_reservation_id, verified_reservation_id, observed_warehouse, actor, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    UUID.randomUUID(),
                    order.getId(),
                    line.getId(),
                    line.getReservationId(),
                    after,
                    proof == null ? null : proof.warehouseId(),
                    ACTOR,
                    reason);
            if (proof == null) {
                jdbc.update(
                        """
                    INSERT INTO orders.legacy_reservation_reference_line
                        (line_id, order_id, product_id, sku, quantity, original_status, original_order_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """,
                        line.getId(),
                        order.getId(),
                        line.getProductId(),
                        line.getProductSku(),
                        line.getQuantity(),
                        order.getStatus().name(),
                        order.getVersion());
            }
            jdbc.update(
                    "UPDATE orders.order_line SET reservation_id = ?, reservation_verified = ? WHERE id = ? AND order_id = ?",
                    after,
                    proof != null,
                    line.getId(),
                    order.getId());
        }
        if (irrecoverable
                && order.getStatus() != OrderStatus.DELIVERED
                && order.getStatus() != OrderStatus.CANCELLED) {
            orders.findAndApply(
                    order.getId(),
                    current -> {
                        current.cancel(ACTOR, reason);
                        if (current.getStatus() == OrderStatus.CANCELLED)
                            events.publishCancelled(current, ACTOR, reason);
                        cleanup.trackCancellation(current.getId(), ACTOR, reason);
                    });
            entities.flush();
            entities.clear();
        } else {
            if (jdbc.update(
                            "UPDATE orders.purchase_order SET version = version + 1, updated_at = clock_timestamp() WHERE id = ? AND version = ?",
                            order.getId(),
                            snapshot.getVersion())
                    != 1) throw new IllegalStateException("Legacy order changed during resolution");
            entities.clear();
        }
        var current = orders.findByIdFull(order.getId());
        var missing =
                current.getLines().stream()
                        .filter(line -> line.getReservationId() == null)
                        .toList();
        if (!missing.isEmpty()
                && (current.getStatus() == OrderStatus.CANCELLED
                        || current.getStatus() == OrderStatus.DELIVERED)) {
            int inserted = 0;
            for (var line : missing) {
                inserted +=
                        jdbc.update(
                                """
                    INSERT INTO orders.terminal_reservation_reference_disposition
                        (line_id, order_id, id, policy, previous_order_version, actor, reason, evidence)
                    VALUES (?, ?, ?, 'IRRECOVERABLE_LEGACY_REFERENCE', ?, ?, ?, ?)
                    ON CONFLICT (line_id) DO NOTHING
                    """,
                                line.getId(),
                                current.getId(),
                                UUID.randomUUID(),
                                current.getVersion(),
                                ACTOR,
                                reason,
                                "Complete privileged inventory lookup could not verify unique matching original holds; approved upgrade policy 2026-11-10");
            }
            if (inserted > 0)
                jdbc.update(
                        "UPDATE orders.purchase_order SET version = version + 1, updated_at = clock_timestamp() WHERE id = ?",
                        current.getId());
        }
        if (current.getStatus() == OrderStatus.CANCELLING) {
            jdbc.update(
                    "UPDATE orders.legacy_lifecycle_resolution SET resolution = 'CANCELLATION_PENDING', evidence = ? WHERE order_id = ?",
                    reason,
                    order.getId());
        } else {
            jdbc.update(
                    "UPDATE orders.legacy_lifecycle_resolution SET resolved_at = clock_timestamp(), resolution = ?, evidence = ? WHERE order_id = ?",
                    irrecoverable ? "AUDITED_TERMINAL_DISPOSITION" : "VERIFIED_REFERENCES",
                    reason,
                    order.getId());
        }
    }

    @Transactional(timeout = 10)
    public void finishCancellation(OrderLifecycleStore.Claim claim) {
        leases.requireCurrent(claim);
        if (jdbc.queryForList(
                        "SELECT order_id FROM orders.legacy_lifecycle_resolution WHERE order_id = ? AND resolved_at IS NULL AND resolution = 'CANCELLATION_PENDING' FOR UPDATE",
                        UUID.class,
                        claim.orderId())
                .isEmpty()) return;
        var order = orders.findByIdFull(claim.orderId());
        if (order.getStatus() != OrderStatus.CANCELLED)
            throw new IllegalStateException("Legacy compensation has not completed");
        int count = 0;
        for (var line : order.getLines()) {
            if (line.getReservationId() != null) continue;
            count +=
                    jdbc.update(
                            """
                INSERT INTO orders.terminal_reservation_reference_disposition
                    (line_id, order_id, id, policy, previous_order_version, actor, reason, evidence)
                VALUES (?, ?, ?, 'IRRECOVERABLE_LEGACY_REFERENCE', ?, ?, ?, ?)
                ON CONFLICT (line_id) DO NOTHING
                """,
                            line.getId(),
                            order.getId(),
                            UUID.randomUUID(),
                            order.getVersion(),
                            ACTOR,
                            "Approved upgrade: irrecoverable historical reservation references",
                            "Complete original inventory lookup and durable stock compensation; approved upgrade policy 2026-11-10");
        }
        if (count > 0)
            jdbc.update(
                    "UPDATE orders.purchase_order SET version = version + 1, updated_at = clock_timestamp() WHERE id = ?",
                    order.getId());
        jdbc.update(
                "UPDATE orders.legacy_lifecycle_resolution SET resolved_at = clock_timestamp(), resolution = 'AUDITED_TERMINAL_DISPOSITION' WHERE order_id = ?",
                order.getId());
    }
}

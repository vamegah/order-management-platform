package com.example.omp.order;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Privileged test fixture modelling a captured pre-upgrade aggregate. Never used by runtime code.
 */
public final class LegacyReferenceFixture {
    private LegacyReferenceFixture() {}

    public static void capture(JdbcTemplate jdbc, UUID order) {
        jdbc.update(
                """
            INSERT INTO orders.legacy_lifecycle_resolution(order_id, original_status, original_version)
            SELECT id, status, version FROM orders.purchase_order WHERE id = ?
            ON CONFLICT DO NOTHING
            """,
                order);
        jdbc.update(
                """
            INSERT INTO orders.legacy_lifecycle_line
                (line_id, order_id, product_id, sku, quantity, original_reservation_id, original_warehouse)
            SELECT id, order_id, product_id, product_sku, quantity, reservation_id, reservation_warehouse_id
            FROM orders.order_line WHERE order_id = ? ON CONFLICT DO NOTHING
            """,
                order);
        jdbc.update(
                "UPDATE orders.order_line SET reservation_verified = false WHERE order_id = ?",
                order);
    }
}

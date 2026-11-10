package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReservationWarehouseUpgradeIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withUrlParam("currentSchema", "orders,public");

    @Test
    void anExistingDatabaseKeepsReferencesAndBusinessStateWithoutInventingWarehouseIntent() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS citext");
        Flyway.configure()
                .dataSource(source)
                .schemas("orders")
                .locations("classpath:db/migration")
                .target("13")
                .load()
                .migrate();
        UUID line =
                jdbc.queryForObject(
                        "SELECT id FROM orders.order_line ORDER BY id LIMIT 1", UUID.class);
        UUID originalReference = UUID.randomUUID();
        // Synthetic pre-V14 reference in this isolated migration fixture, not a discovered hold.
        jdbc.update(
                "UPDATE orders.order_line SET reservation_id = ? WHERE id = ?",
                originalReference,
                line);
        var beforeLines = snapshot(jdbc, "order_line");
        var beforeOrders = snapshot(jdbc, "purchase_order");
        var beforeHistory = snapshot(jdbc, "order_status_history");
        var checksums =
                jdbc.queryForList(
                        "SELECT version, checksum FROM orders.flyway_schema_history WHERE success ORDER BY installed_rank");
        var current =
                Flyway.configure()
                        .dataSource(source)
                        .schemas("orders")
                        .locations("classpath:db/migration")
                        .load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(snapshot(jdbc, "order_line")).isEqualTo(beforeLines);
        assertThat(snapshot(jdbc, "purchase_order")).isEqualTo(beforeOrders);
        assertThat(snapshot(jdbc, "order_status_history")).isEqualTo(beforeHistory);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reservation_id FROM orders.order_line WHERE id = ?",
                                UUID.class,
                                line))
                .isEqualTo(originalReference);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.order_line WHERE reservation_warehouse_id IS NOT NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT version, checksum FROM orders.flyway_schema_history WHERE success AND (version IS NULL OR version <> '14') ORDER BY installed_rank"))
                .isEqualTo(checksums);
        for (String table :
                List.of(
                        "reservation_reference_repair",
                        "terminal_reservation_reference_disposition",
                        "checkout_attempt",
                        "outbox_event"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders." + table, Integer.class))
                    .as(table)
                    .isZero();
        for (String invalid : List.of("", " ", "\t", " WH-1", "WH-1 ", "x".repeat(65))) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE orders.order_line SET reservation_warehouse_id = ? WHERE id = ?",
                                            invalid,
                                            line))
                    .isInstanceOf(DataAccessException.class);
            assertThat(snapshot(jdbc, "order_line")).isEqualTo(beforeLines);
        }
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(snapshot(jdbc, "purchase_order")).isEqualTo(beforeOrders);
    }

    private List<String> snapshot(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList(
                "SELECT (to_jsonb(r) - 'reservation_warehouse_id')::text FROM orders."
                        + table
                        + " r ORDER BY id",
                String.class);
    }
}

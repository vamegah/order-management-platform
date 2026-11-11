package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.dto.ReservationReferenceCensusPage.Issue;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReservationReferenceUpgradeIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withUrlParam("currentSchema", "orders,public");

    @Test
    void preReferenceDatabaseUpgradesWithoutInventingHoldsOrRewritingHistoricalOrders() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS citext");
        Flyway.configure()
                .dataSource(source)
                .schemas("orders")
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'orders' AND table_name = 'order_line' AND column_name = 'reservation_id'",
                                Integer.class))
                .isZero();
        var orders = snapshot(jdbc, "purchase_order", "version");
        var lines = snapshot(jdbc, "order_line", "reservation_id");
        var history = snapshot(jdbc, "order_status_history", "unused");
        var checksums = originalChecksums(jdbc);
        assertThat(orders).hasSize(3);
        assertThat(lines).hasSize(4);
        assertThat(history).isNotEmpty();
        assertThat(checksums).hasSize(3);

        var current =
                Flyway.configure()
                        .dataSource(source)
                        .schemas("orders")
                        .locations("classpath:db/migration")
                        .load();
        assertThat(current.migrate().migrationsExecuted).isGreaterThan(0);
        current.validate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.order_line WHERE reservation_warehouse_id IS NOT NULL",
                                Integer.class))
                .isZero();

        assertThat(snapshot(jdbc, "purchase_order", "version")).isEqualTo(orders);
        assertThat(snapshot(jdbc, "order_line", "reservation_id")).isEqualTo(lines);
        assertThat(snapshot(jdbc, "order_status_history", "unused")).isEqualTo(history);
        assertThat(originalChecksums(jdbc)).isEqualTo(checksums);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.order_line WHERE reservation_id IS NOT NULL",
                                Integer.class))
                .isZero();

        var page = new ReservationReferenceCensus(jdbc).scan(null, 100);
        assertThat(page.scanned()).isEqualTo(4);
        assertThat(page.nextAfterLineId()).isNull();
        assertThat(page.findings()).hasSize(4).allMatch(f -> f.issue() == Issue.MISSING_REFERENCE);
        assertThat(page.findings())
                .extracting(f -> f.status())
                .contains("DELIVERED", "CONFIRMED", "DRAFT");
        assertThat(page.findings())
                .allMatch(f -> f.orderVersion() == 0 && f.reservationId() == null);
        for (String table :
                List.of("reservation_reference_repair", "checkout_attempt", "outbox_event"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders." + table, Integer.class))
                    .as(table)
                    .isZero();

        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(snapshot(jdbc, "purchase_order", "version")).isEqualTo(orders);
        assertThat(snapshot(jdbc, "order_line", "reservation_id")).isEqualTo(lines);
        assertThat(snapshot(jdbc, "order_status_history", "unused")).isEqualTo(history);
    }

    private List<String> snapshot(JdbcTemplate jdbc, String table, String addedColumn) {
        return jdbc.queryForList(
                "SELECT (to_jsonb(historical_row) - ? - 'reservation_warehouse_id' - 'reservation_verified')::text FROM orders."
                        + table
                        + " historical_row ORDER BY id",
                String.class,
                addedColumn);
    }

    private List<Map<String, Object>> originalChecksums(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT version, checksum FROM orders.flyway_schema_history WHERE success AND version IN ('1', '2', '3') ORDER BY version");
    }
}

package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.dto.TerminalReservationDispositionRequest;
import com.example.omp.order.error.InvalidOrderStateException;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class TerminalReservationDispositionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    private static final UUID DELIVERED = uuid(301), ACTIVE = uuid(302), CANCELLED = uuid(303);
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TerminalReservationDispositionService service;

    @Configuration
    @EnableTransactionManagement
    static class Beans {
        @Bean
        DataSource source() {
            return new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TerminalReservationDispositionService dispositions(JdbcTemplate jdbc) {
            return new TerminalReservationDispositionService(jdbc);
        }
    }

    @BeforeEach
    void upgradeHistoricalDatabase() {
        var source = new Beans().source();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS citext");
        Flyway.configure()
                .dataSource(source)
                .schemas("orders")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(source)
                .schemas("orders")
                .locations("classpath:db/migration")
                .target("12")
                .load()
                .migrate();
        jdbc.update(
                "UPDATE orders.purchase_order SET status = 'CANCELLED' WHERE id = ?", CANCELLED);
        Flyway.configure()
                .dataSource(source)
                .schemas("orders")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        context = new AnnotationConfigApplicationContext(Beans.class);
        service = context.getBean(TerminalReservationDispositionService.class);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @Test
    void terminalExceptionAuditsMissingMetadataWithoutInventingAReferenceOrChangingBusinessState() {
        var before = businessState(DELIVERED);
        var plan = request(DELIVERED);
        assertThat(service.inspect(DELIVERED).lines())
                .allMatch(line -> line.eligible() && line.audit() == null);
        var result = service.record(DELIVERED, plan, "verified-admin");
        assertThat(result.recorded()).isEqualTo(1);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.lines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.reservationId()).isNull();
                            assertThat(line.audit().policy())
                                    .isEqualTo("IRRECOVERABLE_LEGACY_REFERENCE");
                            assertThat(line.audit().actor()).isEqualTo("verified-admin");
                            assertThat(line.audit().reason())
                                    .isEqualTo("Historical investigation completed");
                            assertThat(line.audit().evidence())
                                    .isEqualTo("Incident INC-123: no recoverable original hold");
                            assertThat(line.audit().previousOrderVersion()).isZero();
                            assertThat(line.audit().id()).isNotNull();
                            assertThat(line.audit().recordedAt()).isNotNull();
                        });
        var retry = service.record(DELIVERED, plan, "verified-admin");
        assertThat(retry.recorded()).isZero();
        assertThat(retry.version()).isEqualTo(1);
        assertThat(retry.lines()).isEqualTo(result.lines());
        assertThat(count(DELIVERED)).isEqualTo(1);
        assertThat(businessState(DELIVERED)).isEqualTo(before);
        assertThat(new ReservationReferenceCensus(jdbc).scan(null, 100).findings())
                .hasSize(4)
                .allMatch(f -> f.reservationId() == null);
    }

    @Test
    void activeOrdersFutureLinesAndChangedHistoricalIdentityCannotReceiveAnException() {
        assertThatThrownBy(() -> service.record(ACTIVE, request(ACTIVE), "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        UUID future = UUID.randomUUID();
        // Future aggregates cannot enter the historical policy: final integrity now
        // rejects a terminal order without verified lines before it can be persisted.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO orders.purchase_order (id, order_number, customer_id, customer_email,
                                            status, currency, shipping_line1, shipping_city, shipping_postal_code, shipping_country)
                                        SELECT ?, ?, customer_id, customer_email, 'DELIVERED', currency,
                                            shipping_line1, shipping_city, shipping_postal_code, shipping_country
                                        FROM orders.purchase_order WHERE id = ?
                                        """,
                                        future,
                                        "FUTURE-" + future.toString().substring(0, 16),
                                        DELIVERED))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("Order requires 1..100 verified lines");
        jdbc.update(
                "UPDATE orders.order_line SET quantity = 2, line_total = 2 * unit_price_amount WHERE order_id = ?",
                DELIVERED);
        assertThatThrownBy(() -> service.record(DELIVERED, request(DELIVERED), "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.terminal_reservation_reference_disposition",
                                Integer.class))
                .isZero();
    }

    @Test
    void stalePlansAndConflictingAttestationsPreserveTheOriginalAudit() {
        var plan = request(DELIVERED);
        var stale =
                new TerminalReservationDispositionRequest(
                        1L, plan.reason(), plan.evidence(), plan.lineIds());
        assertThatThrownBy(() -> service.record(DELIVERED, stale, "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(count(DELIVERED)).isZero();
        var recorded = service.record(DELIVERED, plan, "admin");
        var conflicting =
                new TerminalReservationDispositionRequest(
                        1L, "Different explanation", plan.evidence(), plan.lineIds());
        assertThatThrownBy(() -> service.record(DELIVERED, conflicting, "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(() -> service.record(DELIVERED, plan, "another-admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(service.inspect(DELIVERED).lines()).isEqualTo(recorded.lines());
        assertThat(service.inspect(DELIVERED).version()).isEqualTo(1);
    }

    @Test
    void concurrentLostReplyRetriesRecordOneAuditAndOneVersionIncrement() throws Exception {
        var plan = request(DELIVERED);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int index = 0; index < 2; index++)
                futures.add(
                        workers.submit(
                                () -> {
                                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                    return service.record(DELIVERED, plan, "admin").recorded();
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(15, TimeUnit.SECONDS),
                                    futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        }
        assertThat(count(DELIVERED)).isEqualTo(1);
        assertThat(service.inspect(DELIVERED).version()).isEqualTo(1);
    }

    @Test
    void secondLineFailureRollsBackTheEntireDispositionAndVersion() {
        var plan = request(CANCELLED);
        var before = businessState(CANCELLED);
        jdbc.execute(
                "ALTER TABLE orders.terminal_reservation_reference_disposition ADD CONSTRAINT injected_failure CHECK (line_id <> '00000000-0000-0000-0000-000000000404') NOT VALID");
        assertThatThrownBy(() -> service.record(CANCELLED, plan, "admin"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(CANCELLED)).isZero();
        assertThat(service.inspect(CANCELLED).version()).isZero();
        assertThat(businessState(CANCELLED)).isEqualTo(before);
        jdbc.execute(
                "ALTER TABLE orders.terminal_reservation_reference_disposition DROP CONSTRAINT injected_failure");
        assertThat(service.record(CANCELLED, plan, "admin").recorded()).isEqualTo(2);
        assertThat(service.inspect(CANCELLED).version()).isEqualTo(1);
    }

    @Test
    void anExistingRepairAuditPreventsClaimingAnIrrecoverableReference() {
        jdbc.update(
                """
                INSERT INTO orders.reservation_reference_repair
                    (id, order_id, line_id, reservation_id, previous_order_version, actor, reason)
                VALUES (?, ?, ?, ?, 0, 'operator', 'Original hold recovered')
                """,
                UUID.randomUUID(),
                DELIVERED,
                uuid(401),
                UUID.randomUUID());
        assertThatThrownBy(() -> service.record(DELIVERED, request(DELIVERED), "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(count(DELIVERED)).isZero();
    }

    private TerminalReservationDispositionRequest request(UUID order) {
        var state = service.inspect(order);
        return new TerminalReservationDispositionRequest(
                state.version(),
                "Historical investigation completed",
                "Incident INC-123: no recoverable original hold",
                state.lines().stream()
                        .filter(l -> l.reservationId() == null)
                        .map(l -> l.lineId())
                        .toList());
    }

    private List<Map<String, Object>> businessState(UUID order) {
        return jdbc.queryForList(
                """
                SELECT o.status, o.payment_id,
                    (SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id) FROM orders.order_line l WHERE l.order_id = o.id) AS lines,
                    (SELECT count(*) FROM orders.order_status_history h WHERE h.order_id = o.id) AS history,
                    (SELECT count(*) FROM orders.outbox_event e WHERE e.aggregate_id = o.id::text) AS events
                FROM orders.purchase_order o WHERE o.id = ?
                """,
                order);
    }

    private int count(UUID order) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orders.terminal_reservation_reference_disposition WHERE order_id = ?",
                Integer.class,
                order);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}

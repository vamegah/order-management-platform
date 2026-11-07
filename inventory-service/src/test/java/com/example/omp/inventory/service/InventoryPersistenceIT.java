package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.grpc.handler.BulkOperationsHandler;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.*;
import com.example.omp.proto.inventory.*;
import io.grpc.Status;
import io.grpc.internal.testing.StreamRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Committed stock/hold/outbox checks through the production Spring transaction proxies. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "grpc.server.port=-1",
            "grpc.server.security.enabled=false",
            "spring.flyway.schemas=inventory",
            "spring.flyway.default-schema=inventory",
            "spring.jpa.properties.hibernate.default_schema=inventory",
            "spring.datasource.hikari.maximum-pool-size=4",
            "spring.datasource.hikari.minimum-idle=0",
            "omp.outbox.enabled=false",
            "omp.kafka.provision-topics=false",
            "omp.inventory.expiry.enabled=false",
            "management.tracing.enabled=false"
        })
@ActiveProfiles("test")
@Import(InventoryPersistenceIT.Database.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryPersistenceIT {
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("omp")
                    .withUsername("inventory_svc")
                    .withPassword("test")
                    .withUrlParam("currentSchema", "inventory,public");
        }
    }

    @MockitoBean JwtDecoder decoder;
    @Autowired StockLevelRepository stocks;
    @Autowired ReservationRepository reservations;
    @Autowired ReservationHandler unary;
    @Autowired BulkOperationsHandler bulk;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE inventory.reservation, inventory.stock_level, inventory.outbox_event");
        stocks.saveAndFlush(new StockLevel("GOOD", "WH-1", 10));
        stocks.saveAndFlush(new StockLevel("FAIL", "WH-1", 10));
    }

    @Test
    void bulkCommitsStockHoldAndEventAndRetriesWithoutDuplicatingThem() {
        String order = UUID.randomUUID().toString();
        var request = item("GOOD", order, 3);
        var first = execute(request);
        assertThat(first.getError()).isNull();
        var hold = first.getValues().getFirst().getReservations(0);
        assertThat(hold.getCreatedAtEpochMillis()).isPositive();
        assertThat(reserved("GOOD")).isEqualTo(3);
        assertThat(events()).isEqualTo(1);
        assertThat(execute(request).getValues().getFirst().getReservations(0)).isEqualTo(hold);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(events()).isEqualTo(1);

        var release =
                ReleaseReservationRequest.newBuilder()
                        .setReservationId(hold.getId())
                        .setReason("Cancelled")
                        .build();
        assertThat(unary.release(release).getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        unary.release(release);
        assertThat(reserved("GOOD")).isZero();
        assertThat(events()).isEqualTo(2);
        assertThat(
                        unary.get(
                                        GetReservationRequest.newBuilder()
                                                .setReservationId(hold.getId())
                                                .build())
                                .getOrderId())
                .isEqualTo(order);
    }

    @Test
    void partialBusinessFailuresDoNotCreateHoldsOrEventsForRejectedItems() {
        String order = UUID.randomUUID().toString();
        var output =
                execute(
                        item("GOOD", order, 2),
                        item("FAIL", order, 11),
                        item("MISSING", order, 1),
                        item("GOOD", order, 0));
        assertThat(output.getError()).isNull();
        assertThat(output.getValues())
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.getReservationsCount()).isEqualTo(1);
                            assertThat(result.getFailuresCount()).isEqualTo(3);
                        });
        assertThat(reserved("GOOD")).isEqualTo(2);
        assertThat(reserved("FAIL")).isZero();
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void changedAndTerminalRetriesCannotReuseAnIncompatibleHold() {
        String order = UUID.randomUUID().toString();
        var item = item("GOOD", order, 2);
        var hold = execute(item).getValues().getFirst().getReservations(0);
        assertThat(execute(item("GOOD", order, 3)).getValues().getFirst().getFailuresCount())
                .isEqualTo(1);
        assertThat(
                        execute(item.toBuilder().setWarehouseId("OTHER").build())
                                .getValues()
                                .getFirst()
                                .getFailuresCount())
                .isEqualTo(1);
        var commit = CommitReservationRequest.newBuilder().setReservationId(hold.getId()).build();
        assertThat(unary.commit(commit).getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
        unary.commit(commit);
        assertThat(execute(item).getValues().getFirst().getFailuresCount()).isEqualTo(1);
        assertThat(stocks.findBySkuAndWarehouseId("GOOD", "WH-1").orElseThrow().getQuantityOnHand())
                .isEqualTo(8);
        assertThat(reserved("GOOD")).isZero();
        assertThat(events()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                unary.release(
                                        ReleaseReservationRequest.newBuilder()
                                                .setReservationId(hold.getId())
                                                .build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eventFailureRollsBackItsItemAndWholeStreamReplayRecoversEarlierCommits() {
        String order = UUID.randomUUID().toString();
        jdbc.execute(
                "ALTER TABLE inventory.outbox_event ADD CONSTRAINT reject_fail_sku "
                        + "CHECK (payload->>'sku' <> 'FAIL') NOT VALID");
        try {
            var failed = execute(item("GOOD", order, 2), item("FAIL", order, 3));
            assertThat(Status.fromThrowable(failed.getError()).getCode())
                    .isEqualTo(Status.Code.INTERNAL);
            assertThat(failed.getValues()).isEmpty();
            assertThat(reserved("GOOD")).isEqualTo(2);
            assertThat(reserved("FAIL")).isZero();
            assertThat(reservations.count()).isEqualTo(1);
            assertThat(events()).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE inventory.outbox_event DROP CONSTRAINT reject_fail_sku");
        }
        var recovered = execute(item("GOOD", order, 2), item("FAIL", order, 3));
        assertThat(recovered.getError()).isNull();
        assertThat(recovered.getValues().getFirst().getReservationsCount()).isEqualTo(2);
        assertThat(reserved("GOOD")).isEqualTo(2);
        assertThat(reserved("FAIL")).isEqualTo(3);
        assertThat(events()).isEqualTo(2);
    }

    @Test
    void malformedOrMixedOrderStreamsLeaveNoCommittedEffects() {
        var result =
                execute(
                        item("GOOD", UUID.randomUUID().toString(), 2),
                        item("FAIL", UUID.randomUUID().toString(), 3));
        assertThat(Status.fromThrowable(result.getError()).getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(reserved("GOOD")).isZero();
        assertThat(reservations.count()).isZero();
        assertThat(events()).isZero();
    }

    @Test
    void expiryIsBoundedAndLeavesRecentAndTerminalReservationsUntouched() {
        var first = hold("GOOD", 2);
        var second = hold("GOOD", 3);
        var recent = hold("GOOD", 1);
        var committed = hold("GOOD", 1);
        var released = hold("GOOD", 1);
        unary.commit(
                CommitReservationRequest.newBuilder().setReservationId(committed.getId()).build());
        unary.release(
                ReleaseReservationRequest.newBuilder().setReservationId(released.getId()).build());
        stale(first, 120);
        stale(second, 60);
        stale(committed, 180);
        stale(released, 180);
        long before = events();
        var metrics = new SimpleMeterRegistry();
        var job = expiry(metrics, 1);

        job.run();
        assertThat(status(first)).isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThat(status(second)).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
        assertThat(reserved("GOOD")).isEqualTo(4);
        assertThat(events()).isEqualTo(before + 1);
        job.run();
        job.run();

        assertThat(status(second)).isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThat(status(recent)).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
        assertThat(status(committed)).isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
        assertThat(status(released)).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        assertThat(reserved("GOOD")).isEqualTo(1);
        assertThat(stocks.findBySkuAndWarehouseId("GOOD", "WH-1").orElseThrow().getQuantityOnHand())
                .isEqualTo(9);
        assertThat(events()).isEqualTo(before + 2);
        assertThat(metrics.get("inventory.reservation.expiry.completed").counter().count())
                .isEqualTo(2);
    }

    @Test
    void expiryOutboxFailureRollsBackHoldAndStockThenRetryCommitsOnce() {
        var reservation = hold("GOOD", 3);
        stale(reservation, 60);
        long before = events();
        var metrics = new SimpleMeterRegistry();
        var job = expiry(metrics, 10);
        jdbc.execute(
                "ALTER TABLE inventory.outbox_event ADD CONSTRAINT reject_expiry_test CHECK (false) NOT VALID");
        try {
            job.run();
            assertThat(status(reservation)).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
            assertThat(reserved("GOOD")).isEqualTo(3);
            assertThat(events()).isEqualTo(before);
            assertThat(metrics.get("inventory.reservation.expiry.failures").counter().count())
                    .isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE inventory.outbox_event DROP CONSTRAINT reject_expiry_test");
        }

        job.run();
        job.run();
        assertThat(status(reservation)).isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThat(reserved("GOOD")).isZero();
        assertThat(events()).isEqualTo(before + 1);
        assertThat(metrics.get("inventory.reservation.expiry.completed").counter().count())
                .isEqualTo(1);
    }

    @Test
    void expirySkipsAnotherWorkersLockedHoldAndReclaimsItAfterCommit() {
        var locked = hold("GOOD", 2);
        var available = hold("FAIL", 3);
        stale(locked, 120);
        stale(available, 60);
        long before = events();
        var metrics = new SimpleMeterRegistry();
        var job = expiry(metrics, 1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager)
                    .execute(
                            ignored -> {
                                assertThat(
                                                reservations.lockExpiredBatch(
                                                        Instant.now().minus(Duration.ofMinutes(30)),
                                                        1))
                                        .extracting(entry -> entry.getId())
                                        .containsExactly(UUID.fromString(locked.getId()));
                                try {
                                    worker.submit(job::run).get(10, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new AssertionError(
                                            "Expiry worker did not skip the locked hold", failure);
                                }
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT status FROM inventory.reservation WHERE id = ?",
                                                        String.class,
                                                        UUID.fromString(available.getId())))
                                        .isEqualTo("EXPIRED");
                                assertThat(status(locked))
                                        .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
                                return null;
                            });
        }

        job.run();
        job.run();
        assertThat(status(locked)).isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThat(reserved("GOOD")).isZero();
        assertThat(reserved("FAIL")).isZero();
        assertThat(events()).isEqualTo(before + 2);
        assertThat(metrics.get("inventory.reservation.expiry.completed").counter().count())
                .isEqualTo(2);
    }

    private Reservation hold(String sku, int quantity) {
        return unary.reserve(
                ReserveStockRequest.newBuilder()
                        .setOrderId(UUID.randomUUID().toString())
                        .setSku(sku)
                        .setWarehouseId("WH-1")
                        .setQuantity(quantity)
                        .build());
    }

    private void stale(Reservation reservation, int minutes) {
        jdbc.update(
                "UPDATE inventory.reservation SET created_at = now() - (? * interval '1 minute') WHERE id = ?",
                minutes,
                UUID.fromString(reservation.getId()));
    }

    private ReservationStatus status(Reservation reservation) {
        return unary.get(
                        GetReservationRequest.newBuilder()
                                .setReservationId(reservation.getId())
                                .build())
                .getStatus();
    }

    private ReservationExpiryJob expiry(SimpleMeterRegistry metrics, int batchSize) {
        return new ReservationExpiryJob(
                reservations,
                stocks,
                eventPublisher,
                new TransactionTemplate(transactionManager),
                metrics,
                Duration.ofMinutes(30),
                batchSize);
    }

    private int reserved(String sku) {
        return stocks.findBySkuAndWarehouseId(sku, "WH-1").orElseThrow().getQuantityReserved();
    }

    private long events() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event", Long.class);
    }

    private StreamRecorder<BulkReserveResponse> execute(BulkReserveItem... items) {
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = bulk.bulkReserve(output);
        for (var item : items) input.onNext(item);
        input.onCompleted();
        return output;
    }

    private BulkReserveItem item(String sku, String order, int quantity) {
        return BulkReserveItem.newBuilder()
                .setSku(sku)
                .setOrderId(order)
                .setQuantity(quantity)
                .setWarehouseId("WH-1")
                .build();
    }
}

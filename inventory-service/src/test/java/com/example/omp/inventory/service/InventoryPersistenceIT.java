package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.grpc.handler.BulkOperationsHandler;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.*;
import com.example.omp.proto.inventory.*;
import io.grpc.Status;
import io.grpc.internal.testing.StreamRecorder;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
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

    @MockBean JwtDecoder decoder;
    @Autowired StockLevelRepository stocks;
    @Autowired ReservationRepository reservations;
    @Autowired ReservationHandler unary;
    @Autowired BulkOperationsHandler bulk;
    @Autowired JdbcTemplate jdbc;

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

package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.StockLevelRepository;
import com.example.omp.proto.inventory.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "grpc.server.port=-1",
            "grpc.server.security.enabled=false",
            "spring.flyway.schemas=inventory",
            "spring.flyway.default-schema=inventory",
            "spring.jpa.properties.hibernate.default_schema=inventory",
            "spring.datasource.hikari.maximum-pool-size=5",
            "spring.datasource.hikari.minimum-idle=0",
            "omp.outbox.enabled=false",
            "omp.kafka.provision-topics=false",
            "omp.inventory.expiry.enabled=true",
            "omp.inventory.expiry.ttl=PT3S",
            "omp.inventory.expiry.interval-ms=86400000",
            "management.tracing.enabled=false"
        })
@ActiveProfiles("test")
@Import(InventoryPersistenceIT.Database.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser(authorities = "SCOPE_order.reconcile")
class InventoryCompensationIT {
    @MockitoBean JwtDecoder decoder;
    @Autowired ReservationHandler handler;
    @Autowired StockLevelRepository stocks;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE inventory.reservation, inventory.stock_level, inventory.outbox_event, inventory.order_compensation");
        stocks.saveAndFlush(new StockLevel("LIFE-A", "WH-1", 10));
        stocks.saveAndFlush(new StockLevel("LIFE-B", "WH-1", 10));
    }

    private Reservation reserve(UUID order, String sku, int quantity) {
        return handler.reserve(
                ReserveStockRequest.newBuilder()
                        .setOrderId(order.toString())
                        .setSku(sku)
                        .setWarehouseId("WH-1")
                        .setQuantity(quantity)
                        .build());
    }

    private FindReservationsByOrderResponse compensate(UUID order) {
        return handler.compensateOrder(
                CompensateOrderRequest.newBuilder()
                        .setOrderId(order.toString())
                        .setReason("Test-owned cancellation")
                        .build());
    }

    private void quantities(String sku, int onHand, int reserved) {
        var row =
                jdbc.queryForMap(
                        "SELECT quantity_on_hand, quantity_reserved FROM inventory.stock_level WHERE sku = ?",
                        sku);
        assertThat(row.get("quantity_on_hand")).isEqualTo(onHand);
        assertThat(row.get("quantity_reserved")).isEqualTo(reserved);
    }

    @Test
    void partialFulfillmentRestoresCommittedAndActiveLinesExactlyOnceAcrossRetries() {
        UUID order = UUID.randomUUID();
        var committed = reserve(order, "LIFE-A", 3);
        reserve(order, "LIFE-B", 2);
        handler.commit(
                CommitReservationRequest.newBuilder().setReservationId(committed.getId()).build());
        quantities("LIFE-A", 7, 0);
        var response = compensate(order);
        assertThat(response.getReservationsList())
                .extracting(Reservation::getStatus)
                .containsExactlyInAnyOrder(
                        ReservationStatus.RESERVATION_STATUS_RESTOCKED,
                        ReservationStatus.RESERVATION_STATUS_RELEASED);
        long events =
                jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event", Long.class);
        compensate(order);
        quantities("LIFE-A", 10, 0);
        quantities("LIFE-B", 10, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event", Long.class))
                .isEqualTo(events);
        assertThatThrownBy(() -> reserve(order, "LIFE-A", 3))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                handler.commit(
                                        CommitReservationRequest.newBuilder()
                                                .setReservationId(committed.getId())
                                                .build()))
                .isInstanceOf(IllegalStateException.class);
        quantities("LIFE-A", 10, 0);
    }

    @Test
    void outboxFailureRollsBackTheCompensationFenceAndEveryStockMovement() {
        UUID order = UUID.randomUUID();
        var a = reserve(order, "LIFE-A", 3);
        reserve(order, "LIFE-B", 2);
        handler.commit(CommitReservationRequest.newBuilder().setReservationId(a.getId()).build());
        long before =
                jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event", Long.class);
        jdbc.execute(
                "ALTER TABLE inventory.outbox_event ADD CONSTRAINT lifecycle_fault CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> compensate(order)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE inventory.outbox_event DROP CONSTRAINT lifecycle_fault");
        }
        quantities("LIFE-A", 7, 0);
        quantities("LIFE-B", 10, 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM inventory.order_compensation", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.outbox_event", Long.class))
                .isEqualTo(before);
        compensate(order);
        quantities("LIFE-A", 10, 0);
        quantities("LIFE-B", 10, 0);
    }

    @Test
    void aLateCommitUsesTheDatabaseClockEvenBeforeTheExpirySchedulerRuns() {
        UUID order = UUID.randomUUID();
        var hold = reserve(order, "LIFE-A", 2);
        await().atMost(Duration.ofSeconds(7))
                .until(
                        () ->
                                Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                "SELECT created_at + interval '3 seconds' <= clock_timestamp() FROM inventory.reservation WHERE id = ?",
                                                Boolean.class,
                                                UUID.fromString(hold.getId()))));
        var response =
                handler.commit(
                        CommitReservationRequest.newBuilder()
                                .setReservationId(hold.getId())
                                .build());
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        quantities("LIFE-A", 10, 0);
    }

    @Test
    void concurrentCommitAndCancellationAlwaysConserveStockAndLeaveAFence() throws Exception {
        UUID order = UUID.randomUUID();
        var hold = reserve(order, "LIFE-A", 2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var commit =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    handler.commit(
                                            CommitReservationRequest.newBuilder()
                                                    .setReservationId(hold.getId())
                                                    .build());
                                } catch (IllegalStateException cancellationWon) {
                                    /* Durable tombstone won. */
                                }
                                return null;
                            });
            var cancel =
                    executor.submit(
                            () -> {
                                start.await();
                                SecurityContextHolder.getContext()
                                        .setAuthentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        "test-reconciler",
                                                        "unused",
                                                        java.util.List.of(
                                                                new SimpleGrantedAuthority(
                                                                        "SCOPE_order.reconcile"))));
                                try {
                                    return compensate(order);
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            start.countDown();
            commit.get(10, TimeUnit.SECONDS);
            cancel.get(10, TimeUnit.SECONDS);
        }
        quantities("LIFE-A", 10, 0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM inventory.order_compensation WHERE order_id = ?",
                                Long.class,
                                order))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                handler.commit(
                                        CommitReservationRequest.newBuilder()
                                                .setReservationId(hold.getId())
                                                .build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @WithMockUser
    void anOrdinaryCallerCannotCreateACompensationFence() {
        UUID order = UUID.randomUUID();
        reserve(order, "LIFE-A", 1);
        assertThatThrownBy(() -> compensate(order))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        quantities("LIFE-A", 10, 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM inventory.order_compensation", Long.class))
                .isZero();
    }
}

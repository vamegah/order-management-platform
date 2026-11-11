package com.example.omp.integration;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.integration.support.*;
import com.example.omp.proto.inventory.*;
import com.example.omp.proto.payment.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;

/** Independently packaged services, real scheduler, projected JWT, mTLS and natural expiry. */
@Tag("lifecycle-e2e")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CoreLifecycleAcceptanceIT {
    @BeforeAll
    static void settleEarlierAcceptedOrdersThroughTheRealWorker() throws Exception {
        var deployment = new RecoveryDeployment();
        await(
                180,
                () -> {
                    try {
                        var backlog = deployment.lifecycleBacklog();
                        return backlog.path("active_orders").asInt() == 0
                                && backlog.path("pending_cleanup").asInt() == 0
                                && backlog.path("uncompensated_cancellations").asInt() == 0;
                    } catch (Exception unavailable) {
                        return false;
                    }
                });
    }

    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost");

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    private static void await(int seconds, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(500);
        }
        assertThat(condition.getAsBoolean())
                .as("automatic lifecycle recovery within %s seconds", seconds)
                .isTrue();
    }

    private static String status(OrderJourney order) {
        return order.read().path("status");
    }

    private static Reservation reservation(String id) {
        return GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .getReservation(GetReservationRequest.newBuilder().setReservationId(id).build());
    }

    private static Payment payment(String id) {
        return GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .getPayment(GetPaymentRequest.newBuilder().setPaymentId(id).build());
    }

    private static StockLevel stock(Reservation hold) {
        return GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .getStockLevel(
                        GetStockLevelRequest.newBuilder()
                                .setSku(hold.getSku())
                                .setWarehouseId(hold.getWarehouseId())
                                .build());
    }

    @Test
    void anAcceptedPendingOrderExpiresAndCleansUpWithoutAnOperatorTick() throws Exception {
        var order = OrderJourney.create();
        var hold = order.reservation();
        await(
                120,
                () ->
                        "CANCELLED".equals(status(order))
                                && order.payment().getStatus()
                                        == PaymentStatus.PAYMENT_STATUS_CANCELLED);
        assertThat(order.reservation().getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                                        .commitReservation(
                                                CommitReservationRequest.newBuilder()
                                                        .setReservationId(hold.getId())
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        var before = order.read().jsonPath().getList("statusHistory");
        order.transition("cancel", false).then().statusCode(200);
        order.transition("cancel", false).then().statusCode(200);
        assertThat(order.read().jsonPath().getList("statusHistory")).isEqualTo(before);
    }

    @Test
    void aPartialInventoryCommitSurvivesServiceRestartAndIsRestockedBeforeRefund()
            throws Exception {
        var deployment = new RecoveryDeployment();
        var created =
                OrderJourney.authenticated(AuthSupport.aliceToken())
                        .body(
                                Map.of(
                                        "customerId", OrderJourney.CUSTOMER_ID,
                                        "shippingAddress",
                                                Map.of(
                                                        "line1",
                                                        "e2e-lifecycle-" + UUID.randomUUID(),
                                                        "city",
                                                        "Oakland",
                                                        "postalCode",
                                                        "94607",
                                                        "country",
                                                        "US"),
                                        "lines",
                                                List.of(
                                                        Map.of(
                                                                "productId",
                                                                "00000000-0000-0000-0000-000000000204",
                                                                "quantity",
                                                                1),
                                                        Map.of(
                                                                "productId",
                                                                "00000000-0000-0000-0000-000000000202",
                                                                "quantity",
                                                                1))))
                        .post(ORDER_URL + "/api/v1/orders")
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();
        String id = created.path("id"), paymentId = created.path("paymentId");
        List<String> holds = created.jsonPath().getList("lines.reservationId");
        assertThat(holds).hasSize(2);
        var order = new OrderJourney(id, paymentId, holds.getFirst());
        var first = reservation(holds.getFirst());
        int onHand = stock(first).getQuantityOnHand();
        order.confirmPayment();
        order.transition("place", false).then().statusCode(200);
        order.transition("confirm", true).then().statusCode(200);
        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .commitReservation(
                        CommitReservationRequest.newBuilder()
                                .setReservationId(first.getId())
                                .build());
        try {
            deployment.stop("inventory-service");
            order.transition("ship", true).then().statusCode(202);
            // Real TTL passes while the authority is unavailable. No timestamps or leases are
            // edited.
            Thread.sleep(Duration.ofSeconds(23));
        } finally {
            deployment.start("inventory-service");
            GrpcClientFactory.shutdown();
        }
        await(
                150,
                () ->
                        "CANCELLED".equals(status(order))
                                && payment(paymentId).getStatus()
                                        == PaymentStatus.PAYMENT_STATUS_REFUNDED);
        assertThat(reservation(first.getId()).getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RESTOCKED);
        assertThat(reservation(holds.getLast()).getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThat(stock(first).getQuantityOnHand()).isEqualTo(onHand);
        assertThat(payment(paymentId).getFulfillmentState()).isEqualTo("REFUNDABLE");
        assertThat(order.read().jsonPath().<String>getList("statusHistory.toStatus"))
                .contains("FULFILLING", "CANCELLING", "CANCELLED");
        order.transition("cancel", false).then().statusCode(200);
        assertThat(stock(first).getQuantityOnHand()).isEqualTo(onHand);
    }

    @Test
    void concurrentRefundAndShipmentHaveOneDurableOutcome() throws Exception {
        var order = OrderJourney.create();
        order.confirmPayment();
        order.transition("place", false).then().statusCode(200);
        order.transition("confirm", true).then().statusCode(200);
        var start = new CountDownLatch(1);
        boolean refunded;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var shipping =
                    executor.submit(
                            () -> {
                                start.await();
                                return order.transition("ship", true).statusCode();
                            });
            var refund =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    var result =
                                            GrpcClientFactory.paymentClient(
                                                            AuthSupport.adminToken())
                                                    .refundPayment(
                                                            RefundPaymentRequest.newBuilder()
                                                                    .setPaymentId(order.paymentId())
                                                                    .setReason(
                                                                            "Concurrent lifecycle acceptance")
                                                                    .setIdempotencyKey(
                                                                            "lifecycle-refund-"
                                                                                    + UUID
                                                                                            .randomUUID())
                                                                    .build());
                                    assertThat(result.getStatus())
                                            .isEqualTo(RefundStatus.REFUND_STATUS_COMPLETED);
                                    return true;
                                } catch (StatusRuntimeException conflict) {
                                    assertThat(conflict.getStatus().getCode())
                                            .isEqualTo(Status.Code.FAILED_PRECONDITION);
                                    return false;
                                }
                            });
            start.countDown();
            assertThat(shipping.get(40, TimeUnit.SECONDS)).isIn(200, 202, 409);
            refunded = refund.get(40, TimeUnit.SECONDS);
        }
        if (refunded) {
            await(120, () -> "CANCELLED".equals(status(order)));
            assertThat(order.payment().getStatus())
                    .isEqualTo(PaymentStatus.PAYMENT_STATUS_REFUNDED);
            assertThat(order.reservation().getStatus())
                    .isIn(
                            ReservationStatus.RESERVATION_STATUS_RELEASED,
                            ReservationStatus.RESERVATION_STATUS_EXPIRED,
                            ReservationStatus.RESERVATION_STATUS_RESTOCKED);
        } else {
            await(90, () -> "SHIPPED".equals(status(order)));
            assertThat(order.payment().getStatus())
                    .isEqualTo(PaymentStatus.PAYMENT_STATUS_CONFIRMED);
            assertThat(order.payment().getFulfillmentState()).isEqualTo("SHIPPED");
            assertThat(order.reservation().getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
            order.transition("cancel", false).then().statusCode(409);
        }
    }

    @Test
    void theUpgradeResolvesEverySeededLegacyOrderAndOperatorRetryIsAdminOnly() throws Exception {
        var deployment = new RecoveryDeployment();
        await(
                120,
                () -> {
                    try {
                        var state = deployment.legacyLifecycleIntegrity();
                        return state.path("unresolved").asInt() == 0
                                && state.path("uncompensated_cancellations").asInt() == 0
                                && state.path("pending_cleanup").asInt() == 0;
                    } catch (Exception unavailable) {
                        return false;
                    }
                });
        var integrity = deployment.legacyLifecycleIntegrity();
        assertThat(integrity.path("active_unverified").asInt()).isZero();
        assertThat(integrity.path("invented_warehouses").asInt()).isZero();
        assertThat(integrity.path("audited_exceptions").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(payment("00000000-0000-0000-0000-000000000802").getStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_REFUNDED);
        var order = OrderJourney.create();
        String path = ORDER_URL + "/api/v1/order-lifecycle/" + order.id();
        OrderJourney.authenticated(AuthSupport.aliceToken()).get(path).then().statusCode(403);
        OrderJourney.authenticated(AuthSupport.aliceToken())
                .body(Map.of("reason", "Retry"))
                .post(path + "/retry")
                .then()
                .statusCode(403);
        OrderJourney.authenticated(AuthSupport.adminToken()).get(path).then().statusCode(200);
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(Map.of("reason", " "))
                .post(path + "/retry")
                .then()
                .statusCode(400);
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(Map.of("reason", "Verify stock ledger"))
                .post(path + "/retry")
                .then()
                .statusCode(202);
        order.transition("cancel", false).then().statusCode(200);
    }
}

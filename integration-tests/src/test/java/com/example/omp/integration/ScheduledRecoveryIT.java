package com.example.omp.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.example.omp.integration.support.RecoveryDeployment;
import com.example.omp.proto.common.Money;
import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.ReleaseReservationRequest;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.inventory.ReservationStatus;
import com.example.omp.proto.inventory.ReserveStockRequest;
import com.example.omp.proto.payment.CancelPaymentRequest;
import com.example.omp.proto.payment.ConfirmPaymentRequest;
import com.example.omp.proto.payment.CreatePaymentIntentRequest;
import com.example.omp.proto.payment.GetPaymentRequest;
import com.example.omp.proto.payment.Payment;
import com.example.omp.proto.payment.PaymentStatus;
import com.example.omp.proto.payment.RefundPaymentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real scheduled Boot worker, durable database, development issuer and mTLS service containers. */
@Tag("recovery-e2e")
@Timeout(value = 12, unit = TimeUnit.MINUTES)
class ScheduledRecoveryIT {
    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost");
    private static final String METRICS_URL =
            System.getenv()
                    .getOrDefault(
                            "ORDER_METRICS_URL", "http://127.0.0.1:18083/actuator/prometheus");

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    @Test
    void privateProjectionRotationAndContainerRestartsRecoverConfirmedOrphansAutomatically()
            throws Exception {
        var deployment = new RecoveryDeployment();
        awaitOrderRoute();
        var accepted = OrderJourney.create();
        try (AutoCloseable acceptedCleanup =
                        () ->
                                accepted.transition("cancel", false)
                                        .then()
                                        .log()
                                        .ifValidationFails()
                                        .statusCode(200);
                var orphan = Orphan.create(true)) {
            var acceptedPayment = accepted.payment();
            var acceptedReservation = accepted.reservation();
            double before = metric("order_recovery_worker_failures_total");
            try (AutoCloseable restoration = () -> restore(deployment)) {
                deployment.suspendProjection();
                await(30, () -> metric("order_recovery_worker_failures_total") > before);
                orphan.adopt();
                orphan.assertUnchanged();
                String previousStart = deployment.startedAt("order-service");
                deployment.stop("order-service");
                deployment.stop("inventory-service");
                deployment.stop("payment-service");
                deployment.start("inventory-service");
                deployment.start("payment-service");
                GrpcClientFactory.shutdown(); // Refresh only the fixture's observation channels.
                deployment.start("order-recovery-token");
                deployment.start("order-service");
                awaitOrderRoute();
                assertThat(deployment.startedAt("order-service")).isNotEqualTo(previousStart);
                orphan.awaitRecovered(90);
                awaitAcknowledgment();
                assertThat(accepted.payment()).isEqualTo(acceptedPayment);
                assertThat(accepted.reservation()).isEqualTo(acceptedReservation);
                accepted.read().then().body("status", equalTo("DRAFT"));
                String initialProjection = deployment.projectionFingerprint();
                assertThat(initialProjection).matches("[a-f0-9]{64}");
                // Wait for the supervised projector's own refresh, without forcing an exchange.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                boolean rotated = false;
                while (System.nanoTime() < until) {
                    if (!initialProjection.equals(deployment.projectionFingerprint())) {
                        rotated = true;
                        break;
                    }
                    Thread.sleep(1000);
                }
                assertThat(rotated).as("supervised private projection renewed").isTrue();
                try (var afterRotation = Orphan.create(false)) {
                    afterRotation.adopt();
                    afterRotation.awaitRecovered(90);
                }
                var terminalPayment = orphan.payment();
                var terminalReservation = orphan.reservation();
                Thread.sleep(4000);
                assertThat(orphan.payment()).isEqualTo(terminalPayment);
                assertThat(orphan.reservation()).isEqualTo(terminalReservation);
                assertThat(terminalPayment.getEventsList()).hasSize(1);
            }
        }
    }

    @Test
    void aDownstreamOutageRetainsRecoveryUntilTheRealRetryDeadlineAfterRestart() throws Exception {
        var deployment = new RecoveryDeployment();
        awaitOrderRoute();
        try (var orphan = Orphan.create(false);
                AutoCloseable restoration = () -> restore(deployment)) {
            double before = metric("order_recovery_failures_total");
            deployment.stop("inventory-service");
            orphan.adopt();
            await(
                    60,
                    () ->
                            metric("order_recovery_failures_total") > before
                                    && "RECOVERING".equals(orphan.journalStatus()));
            long failedAt = System.nanoTime();
            assertThat(orphan.payment().getStatus())
                    .isEqualTo(PaymentStatus.PAYMENT_STATUS_PENDING);
            deployment.start("inventory-service");
            GrpcClientFactory.shutdown();
            // Recovery lookup failure persists its production five-minute backoff.
            // Restarting the application must not reset it or require manual timestamp edits.
            deployment.stop("order-service");
            deployment.start("order-service");
            awaitOrderRoute();
            long stillBackedOffUntil = failedAt + TimeUnit.SECONDS.toNanos(240);
            while (System.nanoTime() < stillBackedOffUntil) {
                orphan.assertUnchanged();
                Thread.sleep(5000);
            }
            orphan.awaitRecovered(180);
            awaitAcknowledgment();
        }
    }

    private static void restore(RecoveryDeployment deployment) throws Exception {
        // Attempt every restoration even when one service cannot be recovered.
        Exception failure = null;
        for (String service :
                new String[] {
                    "inventory-service", "payment-service", "order-recovery-token", "order-service"
                }) {
            try {
                deployment.start(service);
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        awaitOrderRoute();
    }

    private static void awaitOrderRoute() throws InterruptedException {
        await(
                90,
                () -> {
                    try {
                        var response =
                                given().config(
                                                io.restassured.config.RestAssuredConfig.config()
                                                        .httpClient(
                                                                io.restassured.config
                                                                        .HttpClientConfig
                                                                        .httpClientConfig()
                                                                        .setParam(
                                                                                "http.connection.timeout",
                                                                                2000)
                                                                        .setParam(
                                                                                "http.socket.timeout",
                                                                                5000)))
                                        .get(ORDER_URL + "/actuator/health");
                        return response.statusCode() == 200
                                && "UP".equals(response.jsonPath().getString("status"));
                    } catch (Exception notReady) {
                        return false;
                    }
                });
    }

    private static void await(int seconds, BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(1000);
        }
        assertThat(condition.getAsBoolean()).as("scheduled recovery before deadline").isTrue();
    }

    private static double metric(String name, String... labels) {
        String body =
                OrderJourney.authenticated(AuthSupport.adminToken())
                        .get(METRICS_URL)
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();
        return body.lines()
                .filter(line -> line.startsWith(name + " ") || line.startsWith(name + "{"))
                .filter(line -> java.util.Arrays.stream(labels).allMatch(line::contains))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    private static void awaitAcknowledgment() throws InterruptedException {
        await(
                30,
                () ->
                        metric("order_cleanup_completed_total", "type=\"RESERVATION\"") >= 1
                                && metric("order_cleanup_completed_total", "type=\"PAYMENT\"") >= 1
                                && metric("order_recovery_scans_total") >= 1);
    }

    private static final class Orphan implements AutoCloseable {
        private final String order = UUID.randomUUID().toString();
        private Reservation initialReservation;
        private Payment initialPayment;
        private boolean confirmed;

        static Orphan create(boolean confirmed) throws Exception {
            var orphan = new Orphan();
            try {
                var reservation =
                        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                                .reserveStock(
                                        ReserveStockRequest.newBuilder()
                                                .setOrderId(orphan.order)
                                                .setSku("PHONE-BUDGET-3")
                                                .setWarehouseId("WH-1")
                                                .setQuantity(1)
                                                .build());
                assertThat(UUID.fromString(reservation.getId()).toString())
                        .isEqualTo(reservation.getId());
                assertThat(reservation.getOrderId()).isEqualTo(orphan.order);
                assertThat(reservation.getSku()).isEqualTo("PHONE-BUDGET-3");
                assertThat(reservation.getWarehouseId()).isEqualTo("WH-1");
                assertThat(reservation.getQuantity()).isEqualTo(1);
                assertThat(reservation.getStatus())
                        .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
                orphan.initialReservation = reservation;
                var payment =
                        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                                .createPaymentIntent(
                                        CreatePaymentIntentRequest.newBuilder()
                                                .setOrderId(orphan.order)
                                                .setCustomerId(OrderJourney.CUSTOMER_ID)
                                                .setAmount(
                                                        Money.newBuilder()
                                                                .setAmount("12.34")
                                                                .setCurrency("USD"))
                                                .setIdempotencyKey(
                                                        "scheduled-recovery-intent-" + orphan.order)
                                                .build());
                assertThat(UUID.fromString(payment.getId()).toString()).isEqualTo(payment.getId());
                assertThat(payment.getOrderId()).isEqualTo(orphan.order);
                assertThat(payment.getCustomerId()).isEqualTo(OrderJourney.CUSTOMER_ID);
                assertThat(payment.getAmount().getCurrency()).isEqualTo("USD");
                assertThat(new BigDecimal(payment.getAmount().getAmount()))
                        .isEqualByComparingTo("12.34");
                assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_PENDING);
                orphan.initialPayment = payment;
                if (confirmed) {
                    var confirmedPayment =
                            GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                                    .confirmPayment(
                                            ConfirmPaymentRequest.newBuilder()
                                                    .setPaymentId(payment.getId())
                                                    .setProviderReference(
                                                            "scheduled-recovery-" + orphan.order)
                                                    .build());
                    assertThat(confirmedPayment.getId()).isEqualTo(payment.getId());
                    assertThat(confirmedPayment.getOrderId()).isEqualTo(orphan.order);
                    assertThat(confirmedPayment.getStatus())
                            .isEqualTo(PaymentStatus.PAYMENT_STATUS_CONFIRMED);
                    orphan.initialPayment = confirmedPayment;
                }
                // RPC conversion may precede the transaction's final updated-at callback.
                // Compare later reads with the committed snapshot, including its exact timestamps.
                orphan.initialPayment = orphan.payment();
                orphan.initialReservation = orphan.reservation();
                orphan.confirmed = confirmed;
                return orphan;
            } catch (Exception | AssertionError failure) {
                try {
                    orphan.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        void adopt() {
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .body(Map.of("reason", "scheduled recovery isolated test-owned orphan"))
                    .post(ORDER_URL + "/api/v1/order-recovery/" + order + "/adopt")
                    .then()
                    .statusCode(200)
                    .body("orderId", equalTo(order))
                    .body("submitted", equalTo(true));
        }

        void assertUnchanged() {
            assertThat(reservation()).isEqualTo(initialReservation);
            assertThat(payment()).isEqualTo(initialPayment);
        }

        String journalStatus() {
            return OrderJourney.authenticated(AuthSupport.adminToken())
                    .body(Map.of("reason", "read preserved admission status during recovery"))
                    .post(ORDER_URL + "/api/v1/order-recovery/" + order + "/adopt")
                    .then()
                    .statusCode(200)
                    .body("submitted", equalTo(false))
                    .extract()
                    .path("status");
        }

        void awaitRecovered(int seconds) throws InterruptedException {
            await(
                    seconds,
                    () -> {
                        try {
                            return reservation().getStatus()
                                            == ReservationStatus.RESERVATION_STATUS_RELEASED
                                    && payment().getStatus()
                                            == (confirmed
                                                    ? PaymentStatus.PAYMENT_STATUS_REFUNDED
                                                    : PaymentStatus.PAYMENT_STATUS_CANCELLED);
                        } catch (StatusRuntimeException failure) {
                            if (failure.getStatus().getCode() == Status.Code.UNAVAILABLE
                                    || failure.getStatus().getCode()
                                            == Status.Code.DEADLINE_EXCEEDED) return false;
                            throw failure;
                        }
                    });
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .body(Map.of("reason", "read preserved admission audit after recovery"))
                    .post(ORDER_URL + "/api/v1/order-recovery/" + order + "/adopt")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("RECOVERED"))
                    .body("submitted", equalTo(false))
                    .body("reason", equalTo("scheduled recovery isolated test-owned orphan"));
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .get(ORDER_URL + "/api/v1/orders/" + order)
                    .then()
                    .statusCode(404);
        }

        Reservation reservation() {
            var value =
                    GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                            .getReservation(
                                    GetReservationRequest.newBuilder()
                                            .setReservationId(initialReservation.getId())
                                            .build());
            assertThat(value.getId()).isEqualTo(initialReservation.getId());
            assertThat(value.getOrderId()).isEqualTo(order);
            return value;
        }

        Payment payment() {
            var value =
                    GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                            .getPayment(
                                    GetPaymentRequest.newBuilder()
                                            .setPaymentId(initialPayment.getId())
                                            .build());
            assertThat(value.getId()).isEqualTo(initialPayment.getId());
            assertThat(value.getOrderId()).isEqualTo(order);
            return value;
        }

        @Override
        public void close() {
            try {
                if (initialReservation != null
                        && reservation().getStatus() == ReservationStatus.RESERVATION_STATUS_ACTIVE)
                    GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                            .releaseReservation(
                                    ReleaseReservationRequest.newBuilder()
                                            .setReservationId(initialReservation.getId())
                                            .setReason("scheduled recovery fixture cleanup")
                                            .build());
            } finally {
                if (initialPayment != null) {
                    var status = payment().getStatus();
                    var client = GrpcClientFactory.paymentClient(AuthSupport.adminToken());
                    if (status == PaymentStatus.PAYMENT_STATUS_PENDING)
                        client.cancelPayment(
                                CancelPaymentRequest.newBuilder()
                                        .setPaymentId(initialPayment.getId())
                                        .setReason("scheduled recovery fixture cleanup")
                                        .build());
                    else if (status == PaymentStatus.PAYMENT_STATUS_CONFIRMED
                            || status == PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED)
                        client.refundPayment(
                                RefundPaymentRequest.newBuilder()
                                        .setPaymentId(initialPayment.getId())
                                        .setReason("scheduled recovery fixture cleanup")
                                        .setIdempotencyKey("scheduled-recovery-cleanup-" + order)
                                        .build());
                }
            }
        }
    }
}

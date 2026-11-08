package com.example.omp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.example.omp.proto.common.Money;
import com.example.omp.proto.inventory.FindReservationsByOrderRequest;
import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.ReleaseReservationRequest;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.inventory.ReservationStatus;
import com.example.omp.proto.inventory.ReserveStockRequest;
import com.example.omp.proto.payment.CancelPaymentRequest;
import com.example.omp.proto.payment.ConfirmPaymentRequest;
import com.example.omp.proto.payment.CreatePaymentIntentRequest;
import com.example.omp.proto.payment.FindPaymentsByOrderRequest;
import com.example.omp.proto.payment.GetPaymentRequest;
import com.example.omp.proto.payment.Payment;
import com.example.omp.proto.payment.PaymentStatus;
import com.example.omp.proto.payment.RefundPaymentRequest;
import com.example.omp.proto.payment.RefundStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real issuer/projector JWTs against deployed inventory/payment over the original mTLS identity.
 */
@Tag("e2e")
class RecoveryTransportIT {
    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost");

    @TempDir Path directory;

    @BeforeAll
    static void requireTls() {
        assertThat(System.getenv().getOrDefault("GRPC_TLS_ENABLED", "true"))
                .as("recovery transport acceptance requires TLS")
                .isEqualToIgnoringCase("true");
    }

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    @Test
    void aProjectedWorkloadTokenDiscoversTheTestOwnedResourcesOverMtls() throws Exception {
        var order = OrderJourney.create();
        try {
            String token = project();
            var reservations =
                    GrpcClientFactory.inventoryClient(token)
                            .findReservationsByOrder(reservationsFor(order.id()))
                            .getReservationsList();
            var payments =
                    GrpcClientFactory.paymentClient(token)
                            .findPaymentsByOrder(paymentsFor(order.id()))
                            .getPaymentsList();
            assertThat(reservations)
                    .extracting(Reservation::getId)
                    .containsExactly(order.reservationId());
            assertThat(reservations)
                    .allSatisfy(value -> assertThat(value.getOrderId()).isEqualTo(order.id()));
            assertThat(payments).extracting(Payment::getId).containsExactly(order.paymentId());
            assertThat(payments)
                    .allSatisfy(value -> assertThat(value.getOrderId()).isEqualTo(order.id()));
            order.read().then().body("status", equalTo("DRAFT"));
        } finally {
            order.transition("cancel", false)
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("CANCELLED"));
        }
    }

    @Test
    void aRenewedPrivateProjectionWorksOnTheExistingTlsChannels() throws Exception {
        String id = UUID.randomUUID().toString();
        String first = project();
        assertEmptyLookup(first, id);
        String renewed = project();
        // Boolean assertions keep bearer values out of failure diagnostics.
        assertThat(!first.equals(renewed)).as("the issuer renewed the projection").isTrue();
        assertEmptyLookup(renewed, id);
        assertEmptyLookup(first, id);
        if (supportsPosix()) {
            assertThat(Files.getPosixFilePermissions(directory.resolve("token.jwt")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void bothServicesRejectAChangedSignatureOnOtherwiseValidWorkloadClaims() throws Exception {
        String id = UUID.randomUUID().toString();
        String token = project();
        assertEmptyLookup(token, id);
        String[] parts = token.split("\\.");
        String signature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        String forged = parts[0] + "." + parts[1] + "." + signature;
        assertLookupDenied(forged, id, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void ordinaryAdministratorAndCustomerTokensCannotUseRecoveryDiscovery() {
        String id = UUID.randomUUID().toString();
        assertLookupDenied(AuthSupport.adminToken(), id, Status.Code.PERMISSION_DENIED);
        assertLookupDenied(AuthSupport.aliceToken(), id, Status.Code.PERMISSION_DENIED);
    }

    @Test
    void theWorkloadIdentityCannotAdmitAnOrphanAsAnAdministrator() throws Exception {
        String id = UUID.randomUUID().toString();
        String path = ORDER_URL + "/api/v1/order-recovery/" + id + "/adopt";
        var reason = Map.of("reason", "e2e workload must not acquire administrator privileges");
        OrderJourney.authenticated(project()).body(reason).post(path).then().statusCode(403);
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(reason)
                .post(path)
                .then()
                .statusCode(200)
                .body("submitted", equalTo(true));
    }

    @Test
    void aProjectedWorkloadReleasesAndCancelsTestOwnedOrphanResourcesIdempotently()
            throws Exception {
        String token = project();
        String order = UUID.randomUUID().toString();
        Reservation reservation = null;
        Payment payment = null;
        try {
            reservation = reserveOrphan(order);
            payment = createOrphanPayment(order);
            assertThat(reservation.getOrderId()).isEqualTo(order);
            assertThat(reservation.getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
            assertThat(payment.getOrderId()).isEqualTo(order);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_PENDING);
            assertThat(
                            GrpcClientFactory.inventoryClient(token)
                                    .findReservationsByOrder(reservationsFor(order))
                                    .getReservationsList())
                    .extracting(Reservation::getId)
                    .containsExactly(reservation.getId());
            assertThat(
                            GrpcClientFactory.paymentClient(token)
                                    .findPaymentsByOrder(paymentsFor(order))
                                    .getPaymentsList())
                    .extracting(Payment::getId)
                    .containsExactly(payment.getId());
            var release =
                    ReleaseReservationRequest.newBuilder()
                            .setReservationId(reservation.getId())
                            .setReason("e2e recovery transport fixture")
                            .build();
            var cancel =
                    CancelPaymentRequest.newBuilder()
                            .setPaymentId(payment.getId())
                            .setReason("e2e recovery transport fixture")
                            .build();
            for (int replay = 0; replay < 2; replay++) {
                var released = GrpcClientFactory.inventoryClient(token).releaseReservation(release);
                assertThat(released.getId()).isEqualTo(reservation.getId());
                assertThat(released.getOrderId()).isEqualTo(order);
                assertThat(released.getStatus())
                        .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
                var cancelled = GrpcClientFactory.paymentClient(token).cancelPayment(cancel);
                assertThat(cancelled.getId()).isEqualTo(payment.getId());
                assertThat(cancelled.getOrderId()).isEqualTo(order);
                assertThat(cancelled.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
            }
            assertThat(
                            GrpcClientFactory.inventoryClient(token)
                                    .getReservation(
                                            GetReservationRequest.newBuilder()
                                                    .setReservationId(reservation.getId())
                                                    .build())
                                    .getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
            assertThat(readPayment(token, payment.getId()).getStatus())
                    .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .get(ORDER_URL + "/api/v1/orders/" + order)
                    .then()
                    .statusCode(404);
        } finally {
            try {
                if (reservation != null)
                    GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                            .releaseReservation(
                                    ReleaseReservationRequest.newBuilder()
                                            .setReservationId(reservation.getId())
                                            .setReason("e2e fixture cleanup")
                                            .build());
            } finally {
                if (payment != null)
                    GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                            .cancelPayment(
                                    CancelPaymentRequest.newBuilder()
                                            .setPaymentId(payment.getId())
                                            .setReason("e2e fixture cleanup")
                                            .build());
            }
        }
    }

    @Test
    void aProjectedWorkloadCanRefundAConfirmedOrphanWithOneDurableRefundOnReplay()
            throws Exception {
        String token = project();
        String order = UUID.randomUUID().toString();
        Payment payment = createOrphanPayment(order);
        var request =
                RefundPaymentRequest.newBuilder()
                        .setPaymentId(payment.getId())
                        .setReason("e2e workload remaining-balance compensation")
                        .setIdempotencyKey("e2e-recovery-refund-" + order)
                        .build();
        try {
            var confirmed =
                    GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                            .confirmPayment(
                                    ConfirmPaymentRequest.newBuilder()
                                            .setPaymentId(payment.getId())
                                            .setProviderReference("e2e-recovery-" + order)
                                            .build());
            assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CONFIRMED);
            var refunded = GrpcClientFactory.paymentClient(token).refundPayment(request);
            assertThat(refunded.getId()).isNotBlank();
            assertThat(refunded.getPaymentId()).isEqualTo(payment.getId());
            assertThat(refunded.getStatus()).isEqualTo(RefundStatus.REFUND_STATUS_COMPLETED);
            assertThat(refunded.getAmount().getCurrency()).isEqualTo("USD");
            assertThat(new BigDecimal(refunded.getAmount().getAmount()))
                    .isEqualByComparingTo("12.34");
            Payment terminal = readPayment(token, payment.getId());
            assertThat(terminal.getOrderId()).isEqualTo(order);
            assertThat(terminal.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_REFUNDED);
            assertThat(terminal.getEventsList())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getReason()).contains(refunded.getId()));
            var replay = GrpcClientFactory.paymentClient(token).refundPayment(request);
            assertThat(replay.getId()).isEqualTo(refunded.getId());
            assertThat(replay.getPaymentId()).isEqualTo(payment.getId());
            assertThat(replay.getStatus()).isEqualTo(RefundStatus.REFUND_STATUS_COMPLETED);
            assertThat(readPayment(token, payment.getId())).isEqualTo(terminal);
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .get(ORDER_URL + "/api/v1/orders/" + order)
                    .then()
                    .statusCode(404);
        } finally {
            var admin = GrpcClientFactory.paymentClient(AuthSupport.adminToken());
            var current = readPayment(AuthSupport.adminToken(), payment.getId());
            if (current.getStatus() == PaymentStatus.PAYMENT_STATUS_PENDING)
                admin.cancelPayment(
                        CancelPaymentRequest.newBuilder()
                                .setPaymentId(payment.getId())
                                .setReason("e2e fixture cleanup")
                                .build());
            else if (current.getStatus() == PaymentStatus.PAYMENT_STATUS_CONFIRMED
                    || current.getStatus() == PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED)
                admin.refundPayment(request);
        }
    }

    private static Payment createOrphanPayment(String order) {
        var payment =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                        .createPaymentIntent(
                                CreatePaymentIntentRequest.newBuilder()
                                        .setOrderId(order)
                                        .setCustomerId(OrderJourney.CUSTOMER_ID)
                                        .setAmount(
                                                Money.newBuilder()
                                                        .setAmount("12.34")
                                                        .setCurrency("USD"))
                                        .setIdempotencyKey("e2e-recovery-intent-" + order)
                                        .build());
        // Verify ownership before exposing an identity to any mutation or cleanup action.
        assertThat(UUID.fromString(payment.getId()).toString()).isEqualTo(payment.getId());
        assertThat(payment.getOrderId()).isEqualTo(order);
        assertThat(payment.getCustomerId()).isEqualTo(OrderJourney.CUSTOMER_ID);
        assertThat(payment.getAmount().getCurrency()).isEqualTo("USD");
        assertThat(new BigDecimal(payment.getAmount().getAmount())).isEqualByComparingTo("12.34");
        return payment;
    }

    private static Reservation reserveOrphan(String order) {
        var reservation =
                GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                        .reserveStock(
                                ReserveStockRequest.newBuilder()
                                        .setOrderId(order)
                                        .setSku("PHONE-BUDGET-3")
                                        .setWarehouseId("WH-1")
                                        .setQuantity(1)
                                        .build());
        assertThat(UUID.fromString(reservation.getId()).toString()).isEqualTo(reservation.getId());
        assertThat(reservation.getOrderId()).isEqualTo(order);
        assertThat(reservation.getSku()).isEqualTo("PHONE-BUDGET-3");
        assertThat(reservation.getWarehouseId()).isEqualTo("WH-1");
        assertThat(reservation.getQuantity()).isEqualTo(1);
        return reservation;
    }

    private static Payment readPayment(String token, String id) {
        var payment =
                GrpcClientFactory.paymentClient(token)
                        .getPayment(GetPaymentRequest.newBuilder().setPaymentId(id).build());
        assertThat(payment.getId()).isEqualTo(id);
        return payment;
    }

    private String project() throws Exception {
        Path secret = directory.resolve("client-secret");
        if (!Files.exists(secret)) {
            if (supportsPosix()) {
                Files.setPosixFilePermissions(
                        directory, PosixFilePermissions.fromString("rwx------"));
                Files.createFile(
                        secret,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(secret);
            }
            Files.writeString(
                    secret, developmentSecret(), StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        }
        String keycloak =
                System.getenv()
                        .getOrDefault("KEYCLOAK_URL", "http://keycloak.localhost")
                        .replaceAll("/+$", "");
        var builder =
                new ProcessBuilder(
                                System.getenv().getOrDefault("E2E_PYTHON", "python"),
                                "scripts/project_service_token.py",
                                "--once")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        var environment = builder.environment();
        environment.put(
                "SERVICE_TOKEN_URL", keycloak + "/realms/omp/protocol/openid-connect/token");
        environment.put(
                "SERVICE_TOKEN_ISSUER",
                System.getenv().getOrDefault("OIDC_ISSUER_URI", keycloak + "/realms/omp"));
        environment.put("SERVICE_TOKEN_CLIENT_ID", "order-recovery");
        environment.put("SERVICE_TOKEN_CLIENT_SECRET_FILE", secret.toAbsolutePath().toString());
        environment.put(
                "SERVICE_TOKEN_OUTPUT", directory.resolve("token.jwt").toAbsolutePath().toString());
        if (keycloak.startsWith("http://")) {
            environment.put("SERVICE_TOKEN_ALLOW_HTTP", "development");
        } else {
            environment.remove("SERVICE_TOKEN_ALLOW_HTTP");
        }
        var process = builder.start();
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Development token projection timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "Development token projection failed with exit " + process.exitValue());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
        Path projection = directory.resolve("token.jwt");
        assertThat(Files.size(projection)).as("bounded projected file").isBetween(1L, 16384L);
        return Files.readString(projection, StandardCharsets.US_ASCII);
    }

    private boolean supportsPosix() throws java.io.IOException {
        return Files.getFileStore(directory).supportsFileAttributeView("posix");
    }

    private static String developmentSecret() throws java.io.IOException {
        var realm =
                new ObjectMapper()
                        .readTree(
                                Path.of("infrastructure/keycloak/import/omp-realm.json").toFile());
        for (var client : realm.path("clients")) {
            if ("order-recovery".equals(client.path("clientId").asText())) {
                String value = client.path("secret").asText();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("The committed development recovery client is missing");
    }

    private static FindReservationsByOrderRequest reservationsFor(String order) {
        return FindReservationsByOrderRequest.newBuilder().setOrderId(order).build();
    }

    private static FindPaymentsByOrderRequest paymentsFor(String order) {
        return FindPaymentsByOrderRequest.newBuilder().setOrderId(order).build();
    }

    private static void assertEmptyLookup(String token, String order) {
        assertThat(
                        GrpcClientFactory.inventoryClient(token)
                                .findReservationsByOrder(reservationsFor(order))
                                .getReservationsCount())
                .isZero();
        assertThat(
                        GrpcClientFactory.paymentClient(token)
                                .findPaymentsByOrder(paymentsFor(order))
                                .getPaymentsCount())
                .isZero();
    }

    private static void assertLookupDenied(String token, String order, Status.Code code) {
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.inventoryClient(token)
                                        .findReservationsByOrder(reservationsFor(order)))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure -> assertThat(failure.getStatus().getCode()).isEqualTo(code));
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.paymentClient(token)
                                        .findPaymentsByOrder(paymentsFor(order)))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure -> assertThat(failure.getStatus().getCode()).isEqualTo(code));
    }
}

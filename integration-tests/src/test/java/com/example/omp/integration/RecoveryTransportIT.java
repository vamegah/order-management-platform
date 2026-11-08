package com.example.omp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.example.omp.proto.inventory.FindReservationsByOrderRequest;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.payment.FindPaymentsByOrderRequest;
import com.example.omp.proto.payment.Payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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

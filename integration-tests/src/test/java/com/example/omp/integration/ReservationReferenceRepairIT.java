package com.example.omp.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.example.omp.integration.support.RecoveryDeployment;
import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.ReleaseReservationRequest;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.inventory.ReservationStatus;
import com.example.omp.proto.payment.GetPaymentRequest;
import com.example.omp.proto.payment.Payment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual ingress, issuer, database and mTLS inventory client; metadata loss is a test fixture. */
@Tag("recovery-e2e")
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class ReservationReferenceRepairIT {
    private static final String ORDERS =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost")
                    + "/api/v1/orders";
    private static final String REPAIRS =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost")
                    + "/api/v1/order-reservation-repairs/";
    private static final String REASON = "e2e restore original verified checkout references";

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    @Test
    void originalReferencesAndConcurrentRepairAuditsSurviveRealServiceRestarts() throws Exception {
        var deployment = new RecoveryDeployment();
        try (var checkout = create();
                AutoCloseable restoration = () -> restore(deployment)) {
            var fresh = deployment.referenceSnapshot(checkout.order());
            var holds = checkout.reservations();
            var payment = checkout.payment();
            restart(deployment);
            checkout.assertPublicReferences();
            assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(fresh);
            assertThat(checkout.reservations()).isEqualTo(holds);
            assertThat(checkout.payment()).isEqualTo(payment);

            checkout.loseReferences(deployment);
            var missing = deployment.referenceSnapshot(checkout.order());
            var inspected = inspect(checkout);
            long version = inspected.jsonPath().getLong("version");
            inspected.then().body("missing", equalTo(2)).body("status", equalTo("DRAFT"));
            String token = AuthSupport.adminToken();
            var plan = checkout.plan(version);
            var first = CompletableFuture.supplyAsync(() -> repair(checkout, token, plan));
            var second = CompletableFuture.supplyAsync(() -> repair(checkout, token, plan));
            var replies =
                    List.of(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
            for (var reply : replies) reply.then().statusCode(200);
            assertThat(replies.stream().map(r -> r.jsonPath().getInt("restored")).toList())
                    .containsExactlyInAnyOrder(2, 0);
            for (var reply : replies) {
                reply.then().statusCode(200).body("missing", equalTo(0));
                assertThat(reply.jsonPath().getLong("version")).isEqualTo(version + 1);
            }
            var repaired = deployment.referenceSnapshot(checkout.order());
            assertThat(businessState(repaired)).isEqualTo(businessState(missing));
            var audit = repaired.path("reservation_reference_repair");
            assertThat(audit.size()).isEqualTo(2);
            String subject =
                    new ObjectMapper()
                            .readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
                            .path("sub")
                            .asText();
            assertThat(subject).isNotBlank();
            for (var row : audit) {
                assertThat(row.path("actor").asText()).isEqualTo(subject);
                assertThat(row.path("reason").asText()).isEqualTo(REASON);
                assertThat(row.path("previous_order_version").asLong()).isEqualTo(version);
                assertThat(
                                checkout.lines().stream()
                                        .anyMatch(
                                                line ->
                                                        line.id()
                                                                        .toString()
                                                                        .equals(
                                                                                row.path("line_id")
                                                                                        .asText())
                                                                && line.hold()
                                                                        .toString()
                                                                        .equals(
                                                                                row.path(
                                                                                                "reservation_id")
                                                                                        .asText())))
                        .isTrue();
            }

            restart(deployment);
            checkout.assertPublicReferences();
            assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(repaired);
            repair(checkout, AuthSupport.adminToken(), plan)
                    .then()
                    .statusCode(200)
                    .body("restored", equalTo(0));
            assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(repaired);
            assertThat(checkout.reservations()).isEqualTo(holds);
            assertThat(checkout.payment()).isEqualTo(payment);
        }
    }

    @Test
    void unauthorizedForeignAndIncompletePlansPreserveMissingReferencesAndRealResources()
            throws Exception {
        var deployment = new RecoveryDeployment();
        try (var checkout = create();
                var foreign = create()) {
            var intact = deployment.referenceSnapshot(checkout.order());
            assertThatThrownBy(
                            () ->
                                    deployment.clearTestOwnedReferences(
                                            checkout.order(),
                                            checkout.marker(),
                                            checkout.lines().get(0).id(),
                                            checkout.lines().get(0).hold(),
                                            checkout.lines().get(1).id(),
                                            foreign.lines().get(1).hold()))
                    .isInstanceOf(IOException.class);
            assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(intact);
            checkout.loseReferences(deployment);
            var before = deployment.referenceSnapshot(checkout.order());
            var holds = checkout.reservations();
            var foreignHolds = foreign.reservations();
            var payment = checkout.payment();
            var foreignPayment = foreign.payment();
            var plan = checkout.plan(0);
            given().config(
                            io.restassured.config.RestAssuredConfig.config()
                                    .httpClient(
                                            io.restassured.config.HttpClientConfig
                                                    .httpClientConfig()
                                                    .setParam("http.connection.timeout", 5000)
                                                    .setParam("http.socket.timeout", 20000)))
                    .get(REPAIRS + checkout.order())
                    .then()
                    .statusCode(401);
            OrderJourney.authenticated(AuthSupport.aliceToken())
                    .get(REPAIRS + checkout.order())
                    .then()
                    .statusCode(403);
            OrderJourney.authenticated(AuthSupport.aliceToken())
                    .body(plan)
                    .post(REPAIRS + checkout.order())
                    .then()
                    .statusCode(403);
            String foreignToken = AuthSupport.bobToken();
            var foreignClaims =
                    new ObjectMapper()
                            .readTree(Base64.getUrlDecoder().decode(foreignToken.split("\\.")[1]));
            assertThat(foreignClaims.path("customer_id").asText())
                    .isEqualTo("00000000-0000-0000-0000-000000000102");
            for (var role : foreignClaims.path("realm_access").path("roles"))
                assertThat(role.asText()).isNotEqualTo("admin");
            OrderJourney.authenticated(foreignToken)
                    .get(REPAIRS + checkout.order())
                    .then()
                    .statusCode(403);
            OrderJourney.authenticated(foreignToken)
                    .body(plan)
                    .post(REPAIRS + checkout.order())
                    .then()
                    .statusCode(403);
            var bindings = checkout.bindings();
            for (var invalid :
                    List.of(
                            Map.of(
                                    "expectedVersion",
                                    0,
                                    "reason",
                                    REASON,
                                    "bindings",
                                    bindings.subList(0, 1)),
                            Map.of(
                                    "expectedVersion",
                                    0,
                                    "reason",
                                    REASON,
                                    "bindings",
                                    List.of(
                                            Map.of(
                                                    "lineId",
                                                    checkout.lines().get(0).id(),
                                                    "reservationId",
                                                    foreign.lines().get(0).hold()),
                                            bindings.get(1))),
                            Map.of(
                                    "expectedVersion",
                                    0,
                                    "reason",
                                    REASON,
                                    "bindings",
                                    List.of(bindings.get(0), bindings.get(0))))) {
                repair(checkout, AuthSupport.adminToken(), invalid).then().statusCode(400);
                assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(before);
            }
            repair(checkout, AuthSupport.adminToken(), checkout.plan(1)).then().statusCode(409);
            assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(before);
            assertThat(checkout.reservations()).isEqualTo(holds);
            assertThat(foreign.reservations()).isEqualTo(foreignHolds);
            assertThat(checkout.payment()).isEqualTo(payment);
            assertThat(foreign.payment()).isEqualTo(foreignPayment);
            repair(checkout, AuthSupport.adminToken(), plan)
                    .then()
                    .statusCode(200)
                    .body("restored", equalTo(2));
            checkout.assertPublicReferences();
        }
    }

    @Test
    void anInactiveOriginalHoldCannotBeBoundOrPartiallyAudited() throws Exception {
        var deployment = new RecoveryDeployment();
        try (var checkout = create()) {
            checkout.loseReferences(deployment);
            release(checkout.lines().get(1).hold());
            var holds = checkout.reservations();
            assertThat(holds.get(0).getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
            assertThat(holds.get(1).getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
            var before = deployment.referenceSnapshot(checkout.order());
            var payment = checkout.payment();
            repair(checkout, AuthSupport.adminToken(), checkout.plan(0)).then().statusCode(409);
            assertThat(deployment.referenceSnapshot(checkout.order())).isEqualTo(before);
            assertThat(checkout.reservations()).isEqualTo(holds);
            assertThat(checkout.payment()).isEqualTo(payment);
            assertThat(before.path("reservation_reference_repair").size()).isZero();
        }
    }

    private static JsonNode businessState(JsonNode snapshot) {
        ObjectNode copy = snapshot.deepCopy();
        ((ObjectNode) copy.path("order")).remove(List.of("version", "updated_at"));
        for (var line : copy.path("order_line")) ((ObjectNode) line).remove("reservation_id");
        copy.remove("reservation_reference_repair");
        return copy;
    }

    private static Response inspect(Checkout checkout) {
        return OrderJourney.authenticated(AuthSupport.adminToken())
                .get(REPAIRS + checkout.order())
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private static Response repair(Checkout checkout, String token, Object plan) {
        return OrderJourney.authenticated(token).body(plan).post(REPAIRS + checkout.order());
    }

    private static void restart(RecoveryDeployment deployment) throws Exception {
        String previous = deployment.startedAt("order-service");
        for (String service : List.of("order-service", "inventory-service", "payment-service"))
            deployment.stop(service);
        restore(deployment);
        assertThat(deployment.startedAt("order-service")).isNotEqualTo(previous);
    }

    private static void restore(RecoveryDeployment deployment) throws Exception {
        var inventory = CompletableFuture.runAsync(() -> start(deployment, "inventory-service"));
        var payment = CompletableFuture.runAsync(() -> start(deployment, "payment-service"));
        CompletableFuture.allOf(inventory, payment).get(5, TimeUnit.MINUTES);
        deployment.start("order-service");
        GrpcClientFactory.shutdown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            if (OrderJourney.authenticated(AuthSupport.adminToken())
                            .get(REPAIRS + "00000000-0000-0000-0000-000000000303")
                            .statusCode()
                    == 200) return;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Order ingress did not recover");
    }

    private static void start(RecoveryDeployment deployment, String service) {
        try {
            deployment.start(service);
        } catch (Exception failure) {
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    private static Checkout create() {
        String marker = "e2e-reference-repair-" + UUID.randomUUID();
        var response =
                OrderJourney.authenticated(AuthSupport.aliceToken())
                        .config(
                                io.restassured.config.RestAssuredConfig.config()
                                        .httpClient(
                                                io.restassured.config.HttpClientConfig
                                                        .httpClientConfig()
                                                        .setParam("http.connection.timeout", 5000)
                                                        .setParam("http.socket.timeout", 60000)))
                        .body(
                                Map.of(
                                        "customerId",
                                        OrderJourney.CUSTOMER_ID,
                                        "shippingAddress",
                                        Map.of(
                                                "line1",
                                                marker,
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
                                                        2))))
                        .post(ORDERS)
                        .then()
                        .statusCode(201)
                        .body("status", equalTo("DRAFT"))
                        .body("shippingAddress.line1", equalTo(marker))
                        .extract()
                        .response();
        List<Map<String, Object>> data = response.jsonPath().getList("lines");
        var lines =
                data.stream()
                        .map(
                                line ->
                                        new Line(
                                                UUID.fromString((String) line.get("id")),
                                                UUID.fromString((String) line.get("reservationId")),
                                                (String) line.get("productSku"),
                                                ((Number) line.get("quantity")).intValue()))
                        .toList();
        var checkout =
                new Checkout(
                        UUID.fromString(response.path("id")),
                        UUID.fromString(response.path("paymentId")),
                        marker,
                        lines);
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(Line::hold).toList()).doesNotHaveDuplicates();
        checkout.assertPublicReferences();
        var reservations = checkout.reservations();
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var reservation = reservations.get(index);
            assertThat(reservation.getId()).isEqualTo(line.hold().toString());
            assertThat(reservation.getOrderId()).isEqualTo(checkout.order().toString());
            assertThat(reservation.getSku()).isEqualTo(line.sku());
            assertThat(reservation.getQuantity()).isEqualTo(line.quantity());
            assertThat(reservation.getWarehouseId()).isEqualTo("WH-1");
            assertThat(reservation.getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
        }
        assertThat(checkout.payment().getOrderId()).isEqualTo(checkout.order().toString());
        return checkout;
    }

    private static void release(UUID hold) {
        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .releaseReservation(
                        ReleaseReservationRequest.newBuilder()
                                .setReservationId(hold.toString())
                                .build());
    }

    private record Line(UUID id, UUID hold, String sku, int quantity) {}

    private record Checkout(UUID order, UUID paymentId, String marker, List<Line> lines)
            implements AutoCloseable {
        List<Map<String, UUID>> bindings() {
            return lines.stream()
                    .map(line -> Map.of("lineId", line.id(), "reservationId", line.hold()))
                    .toList();
        }

        Map<String, Object> plan(long version) {
            return Map.of("expectedVersion", version, "reason", REASON, "bindings", bindings());
        }

        void loseReferences(RecoveryDeployment deployment) throws Exception {
            deployment.clearTestOwnedReferences(
                    order,
                    marker,
                    lines.get(0).id(),
                    lines.get(0).hold(),
                    lines.get(1).id(),
                    lines.get(1).hold());
        }

        List<Reservation> reservations() {
            return lines.stream()
                    .map(
                            line ->
                                    GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                                            .getReservation(
                                                    GetReservationRequest.newBuilder()
                                                            .setReservationId(
                                                                    line.hold().toString())
                                                            .build()))
                    .toList();
        }

        Payment payment() {
            return GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                    .getPayment(
                            GetPaymentRequest.newBuilder()
                                    .setPaymentId(paymentId.toString())
                                    .build());
        }

        void assertPublicReferences() {
            var response =
                    OrderJourney.authenticated(AuthSupport.aliceToken())
                            .get(ORDERS + "/" + order)
                            .then()
                            .statusCode(200)
                            .body("status", equalTo("DRAFT"))
                            .extract()
                            .response();
            List<Map<String, Object>> actual = response.jsonPath().getList("lines");
            assertThat(actual).hasSize(lines.size());
            for (var line : lines)
                assertThat(
                                actual.stream()
                                        .anyMatch(
                                                row ->
                                                        line.id().toString().equals(row.get("id"))
                                                                && line.hold()
                                                                        .toString()
                                                                        .equals(
                                                                                row.get(
                                                                                        "reservationId"))))
                        .isTrue();
        }

        @Override
        public void close() {
            try {
                OrderJourney.authenticated(AuthSupport.aliceToken())
                        .body(Map.of("reason", "e2e reference fixture cleanup"))
                        .post(ORDERS + "/" + order + "/cancel")
                        .then()
                        .statusCode(200);
            } finally {
                // Lost pointers are the fixture's deliberate fault; retain the original handles
                // and explicitly release them even when a negative repair remains unresolved.
                for (var line : lines) release(line.hold());
            }
        }
    }
}

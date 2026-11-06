package com.example.omp.order.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.TestSecurityConfig;
import com.example.omp.order.clients.grpc.InventoryClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP authorization, checkout and fulfillment with migrated PostgreSQL and fixture RPCs. */
class OrderBoundaryIT extends AbstractIntegrationTest {
    private static final String OWN = "00000000-0000-0000-0000-000000000301";
    private static final String FOREIGN = "00000000-0000-0000-0000-000000000302";
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryClient inventory;

    @Test
    void verifiedOwnerCanReadByIdAndNumber() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .get("/api/v1/orders/" + OWN)
                .then()
                .statusCode(200)
                .body("id", equalTo(OWN));
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .get("/api/v1/orders/number/ORD-2025-0001001")
                .then()
                .statusCode(200)
                .body("id", equalTo(OWN));
    }

    @Test
    void customerCannotReadOrMutateAnotherCustomersOrder() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .get("/api/v1/orders/" + FOREIGN)
                .then()
                .statusCode(403)
                .contentType("application/problem+json");
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .get("/api/v1/orders/number/ORD-2025-0001002")
                .then()
                .statusCode(403);
        for (String operation : java.util.List.of("place", "cancel")) {
            given().auth()
                    .oauth2(TestSecurityConfig.aliceToken())
                    .contentType("application/json")
                    .post("/api/v1/orders/" + FOREIGN + "/" + operation)
                    .then()
                    .statusCode(403);
        }
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get("/api/v1/orders/" + FOREIGN)
                .then()
                .statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }

    @Test
    void claimlessCustomerCannotReadOrMutateAnOrder() {
        given().auth()
                .oauth2(TestSecurityConfig.legacyToken())
                .get("/api/v1/orders/" + OWN)
                .then()
                .statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.legacyToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + OWN + "/cancel")
                .then()
                .statusCode(403);
    }

    @Test
    void creationCannotChooseAnotherCustomerAndMakesNoDownstreamCalls() {
        var body =
                java.util.Map.of(
                        "customerId",
                        "00000000-0000-0000-0000-000000000102",
                        "shippingAddress",
                        java.util.Map.of(
                                "line1",
                                "1 Main",
                                "city",
                                "Oakland",
                                "postalCode",
                                "94607",
                                "country",
                                "US"),
                        "lines",
                        java.util.List.of(
                                java.util.Map.of(
                                        "productId",
                                        "00000000-0000-0000-0000-000000000201",
                                        "quantity",
                                        1)));
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .body(body)
                .post("/api/v1/orders")
                .then()
                .statusCode(403);
        assertThat(productWireMock.getAllServeEvents()).isEmpty();
        assertThat(customerWireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void invalidBearerIsUnauthorized() {
        given().auth().oauth2("invalid-token").get("/api/v1/orders/" + OWN).then().statusCode(401);
    }

    @Test
    void malformedJsonIsBadRequest() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .body("{")
                .post("/api/v1/orders")
                .then()
                .statusCode(400)
                .contentType("application/problem+json");
    }

    @Test
    void unsupportedRequestMediaTypeUsesProblemDetails() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("text/plain")
                .body("{}")
                .post("/api/v1/orders/{id}/cancel", OWN)
                .then()
                .statusCode(415)
                .contentType("application/problem+json")
                .body("status", equalTo(415))
                .body("title", equalTo("Unsupported Media Type"));
    }

    @Test
    void configuredMoneySortUsesThePersistentAmountField() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("sort", "total.amount,asc")
                .get("/api/v1/orders")
                .then()
                .statusCode(200)
                .body("content", not(empty()));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("sort", "totalAmount,asc")
                .get("/api/v1/orders")
                .then()
                .statusCode(400);
    }

    @Test
    void browserOriginsFollowTheConfiguredAllowlist() {
        given().header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                .options("/api/v1/orders")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "http://localhost:3000");
        given().header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET")
                .options("/api/v1/orders")
                .then()
                .statusCode(403)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void acceptedCheckoutPersistsReservationsAndPaidShipmentCompletesOnce() {
        String id = createDraft();
        UUID order = UUID.fromString(id);
        UUID reservation =
                jdbc.queryForObject(
                        "select reservation_id from orders.order_line where order_id = ?",
                        UUID.class,
                        order);
        UUID payment =
                jdbc.queryForObject(
                        "select payment_id from orders.purchase_order where id = ?",
                        UUID.class,
                        order);
        assertThat(reservation).isNotNull();
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .get("/api/v1/orders/" + id)
                .then()
                .statusCode(200)
                .body("paymentId", equalTo(payment.toString()))
                .body("lines[0].reservationId", equalTo(reservation.toString()));
        assertThat(inventory.get(reservation).orderId()).isEqualTo(order);
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders.checkout_attempt where order_id = ?",
                                String.class,
                                order))
                .isEqualTo("ACCEPTED");
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/place")
                .then()
                .statusCode(200)
                .body("status", equalTo("PLACED"));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/confirm")
                .then()
                .statusCode(409);
        payments.confirm(payment.toString(), "pi-verified-shipment");
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/confirm")
                .then()
                .statusCode(200)
                .body("status", equalTo("CONFIRMED"));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/ship")
                .then()
                .statusCode(200)
                .body("status", equalTo("SHIPPED"));
        assertThat(inventory.get(reservation).status()).isEqualTo("RESERVATION_STATUS_COMMITTED");
        long events = eventCount(order);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/ship")
                .then()
                .statusCode(200)
                .body("status", equalTo("SHIPPED"));
        assertThat(eventCount(order)).isEqualTo(events);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/deliver")
                .then()
                .statusCode(200)
                .body("status", equalTo("DELIVERED"));
    }

    @Test
    void cancellationReleasesDurableReservationsAndRetriesWithoutDuplicateEvents() {
        String id = createDraft();
        UUID order = UUID.fromString(id);
        UUID reservation =
                jdbc.queryForObject(
                        "select reservation_id from orders.order_line where order_id = ?",
                        UUID.class,
                        order);
        UUID payment =
                jdbc.queryForObject(
                        "select payment_id from orders.purchase_order where id = ?",
                        UUID.class,
                        order);
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
        assertThat(inventory.get(reservation).status()).isEqualTo("RESERVATION_STATUS_RELEASED");
        assertThat(payments.get(payment).status()).isEqualTo("PAYMENT_STATUS_CANCELLED");
        long events = eventCount(order);
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .post("/api/v1/orders/" + id + "/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
        assertThat(eventCount(order)).isEqualTo(events);
    }

    private long eventCount(UUID order) {
        return jdbc.queryForObject(
                "select count(*) from orders.outbox_event where aggregate_id = ?",
                Long.class,
                order.toString());
    }
}

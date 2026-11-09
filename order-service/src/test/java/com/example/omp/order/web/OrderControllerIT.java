package com.example.omp.order.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.TestSecurityConfig;
import com.example.omp.order.WireMockDownstreamConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(TestSecurityConfig.class)
class OrderControllerIT extends AbstractIntegrationTest {

    @Test
    void orphanAdmissionRequiresAdministratorAndAValidatedReason() {
        String path = "/api/v1/order-recovery/" + java.util.UUID.randomUUID() + "/adopt";
        given().contentType(ContentType.JSON)
                .body("{\"reason\":\"Reviewed orphan\"}")
                .post(path)
                .then()
                .statusCode(401);
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"Reviewed orphan\"}")
                .post(path)
                .then()
                .statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{}")
                .post(path)
                .then()
                .statusCode(400)
                .body("status", equalTo(400));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"Reviewed orphan\"}")
                .post("/api/v1/order-recovery/not-a-uuid/adopt")
                .then()
                .statusCode(400)
                .body("detail", equalTo("Invalid request parameter"));
    }

    @Test
    void administratorCanAdmitAKnownOrphanOnceWithoutCreatingAnOrder() {
        java.util.UUID id = java.util.UUID.randomUUID();
        String path = "/api/v1/order-recovery/" + id + "/adopt";
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"Reviewed historical orphan\"}")
                .post(path)
                .then()
                .statusCode(200)
                .body("submitted", equalTo(true))
                .body("status", equalTo("FAILED"))
                .body("reason", equalTo("Reviewed historical orphan"));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"Repeated request\"}")
                .post(path)
                .then()
                .statusCode(200)
                .body("submitted", equalTo(false))
                .body("reason", equalTo("Reviewed historical orphan"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption WHERE order_id = ?",
                                Long.class,
                                id))
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.purchase_order WHERE id = ?",
                                Long.class,
                                id))
                .isZero();
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void existingHistoricalAggregateCannotBeAdmittedAsAnOrphan() {
        java.util.UUID id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000301");
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"Reviewed orphan\"}")
                .post("/api/v1/order-recovery/" + id + "/adopt")
                .then()
                .statusCode(409)
                .body("detail", equalTo("An existing order cannot be admitted as an orphan"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void normalFailedCheckoutCannotBeReclassifiedByTheAdministratorAdmissionAction() {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders.checkout_attempt (order_id, status) VALUES (?, 'FAILED')", id);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"Reviewed orphan\"}")
                .post("/api/v1/order-recovery/" + id + "/adopt")
                .then()
                .statusCode(409)
                .body("detail", equalTo("An existing checkout attempt cannot be adopted"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                                String.class,
                                id))
                .isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void reservationCensusRequiresAdministratorAndValidatesBounds() {
        String path = "/api/v1/order-reservation-repairs/census";
        given().get(path).then().statusCode(401);
        given().auth().oauth2(TestSecurityConfig.aliceToken()).get(path).then().statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("limit", 1)
                .get(path)
                .then()
                .statusCode(200)
                .body("scanned", equalTo(1))
                .body("nextAfterLineId", notNullValue());
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("limit", 101)
                .get(path)
                .then()
                .statusCode(400)
                .body("status", equalTo(400));
    }

    @Test
    void reservationRepairInspectionRequiresAdministrator() {
        String path = "/api/v1/order-reservation-repairs/00000000-0000-0000-0000-000000000301";
        given().get(path).then().statusCode(401);
        given().auth().oauth2(TestSecurityConfig.aliceToken()).get(path).then().statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get(path)
                .then()
                .statusCode(200)
                .body("version", notNullValue())
                .body("lines", hasSize(1))
                .body("missing", equalTo(1));
    }

    @Test
    void reservationRepairRejectsNonAdminAndIncompleteBody() {
        String path = "/api/v1/order-reservation-repairs/00000000-0000-0000-0000-000000000301";
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(repairBody(0L, java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
                .post(path)
                .then()
                .statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{}")
                .post(path)
                .then()
                .statusCode(400)
                .body("status", equalTo(400));
    }

    @Test
    void administratorRestoresAnExistingHoldOnceAndKeepsTheRetryIdempotent() {
        java.util.UUID id = java.util.UUID.fromString(createDraft());
        var line =
                jdbc.queryForMap(
                        "SELECT id, reservation_id FROM orders.order_line WHERE order_id = ?", id);
        Long version =
                jdbc.queryForObject(
                        "SELECT version FROM orders.purchase_order WHERE id = ?", Long.class, id);
        jdbc.update("UPDATE orders.order_line SET reservation_id = NULL WHERE order_id = ?", id);
        String body = repairBody(version, line.get("id"), line.get("reservation_id"));
        String path = "/api/v1/order-reservation-repairs/" + id;
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .post(path)
                .then()
                .statusCode(200)
                .body("restored", equalTo(1))
                .body("missing", equalTo(0))
                .body("status", equalTo("DRAFT"));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .post(path)
                .then()
                .statusCode(200)
                .body("restored", equalTo(0));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.reservation_reference_repair WHERE order_id = ?",
                                Long.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void administratorCannotBindAnotherOrdersReservation() {
        java.util.UUID id = java.util.UUID.fromString(createDraft());
        java.util.UUID foreign = java.util.UUID.fromString(createDraft());
        Object foreignReservation =
                jdbc.queryForObject(
                        "SELECT reservation_id FROM orders.order_line WHERE order_id = ?",
                        java.util.UUID.class,
                        foreign);
        Object line =
                jdbc.queryForObject(
                        "SELECT id FROM orders.order_line WHERE order_id = ?",
                        java.util.UUID.class,
                        id);
        Long version =
                jdbc.queryForObject(
                        "SELECT version FROM orders.purchase_order WHERE id = ?", Long.class, id);
        jdbc.update("UPDATE orders.order_line SET reservation_id = NULL WHERE order_id = ?", id);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(repairBody(version, line, foreignReservation))
                .post("/api/v1/order-reservation-repairs/" + id)
                .then()
                .statusCode(400)
                .body("detail", equalTo("The reservation does not match the order line"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT reservation_id FROM orders.order_line WHERE order_id = ?",
                                java.util.UUID.class,
                                id))
                .isNull();
    }

    private String repairBody(Long version, Object line, Object reservation) {
        return """
                {"expectedVersion":%s,"reason":"Verified existing inventory hold","bindings":[{"lineId":"%s","reservationId":"%s"}]}
                """
                .formatted(version, line, reservation);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetStubs() {
        resetWireMocks();
    }

    // -------------------------------------------------------------------------
    // GET /orders
    // -------------------------------------------------------------------------

    @Test
    void listRequiresAdmin() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/orders")
                .then()
                .statusCode(403);
    }

    @Test
    void listReturnsSeedOrdersForAdmin() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("size", 50)
                .when()
                .get("/api/v1/orders")
                .then()
                .statusCode(200)
                .body("content", not(empty()))
                .body("totalElements", greaterThanOrEqualTo(3))
                .body("content.orderNumber", everyItem(notNullValue()));
    }

    // -------------------------------------------------------------------------
    // GET /orders/{id}
    // -------------------------------------------------------------------------

    @Test
    void getByIdReturnsFullOrderWithHistory() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .get("/api/v1/orders/{id}", "00000000-0000-0000-0000-000000000301")
                .then()
                .statusCode(200)
                .body("orderNumber", equalTo("ORD-2025-0001001"))
                .body("status", equalTo("DELIVERED"))
                .body("lines", hasSize(1))
                .body("statusHistory", hasSize(5));
    }

    @Test
    void getByIdReturns404ForUnknown() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .get("/api/v1/orders/{id}", "00000000-0000-0000-0000-000000000999")
                .then()
                .statusCode(404)
                .body("title", equalTo("Order Not Found"));
    }

    // -------------------------------------------------------------------------
    // POST /orders — success path with downstream stubs
    // -------------------------------------------------------------------------

    @Test
    void createSucceedsWhenDownstreamStubsAreReady() {
        String productId = "00000000-0000-0000-0000-000000000201";
        String customerId = "00000000-0000-0000-0000-000000000101";

        WireMockDownstreamConfig.stubProduct(
                productId, "LAPTOP-PRO-14", "Pro Laptop 14\"", "2499.99", "USD");
        WireMockDownstreamConfig.stubCustomer(customerId, "alice@omp.local", "Alice", "Customer");

        String body =
                String.format(
                        """
            {
              "customerId": "%s",
              "shippingAddress": {
                "line1": "200 Shipping Ave",
                "city": "Oakland",
                "state": "CA",
                "postalCode": "94607",
                "country": "US"
              },
              "lines": [{"productId": "%s", "quantity": 1}]
            }
            """,
                        customerId, productId);

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .header("Location", containsString("/api/v1/orders/"))
                .body("status", equalTo("DRAFT"))
                .body("customerId", equalTo(customerId))
                .body("lines", hasSize(1))
                .body("lines[0].productSku", equalTo("LAPTOP-PRO-14"))
                .body("total.amount", equalTo(2499.99f))
                .body("orderNumber", startsWith("ORD-"));
    }

    @Test
    void createFailsWhenProductNotFound() {
        String productId = "00000000-0000-0000-0000-000000000999";
        String customerId = "00000000-0000-0000-0000-000000000101";

        WireMockDownstreamConfig.stubProductNotFound(productId);
        WireMockDownstreamConfig.stubCustomer(customerId, "alice@omp.local", "Alice", "Customer");

        String body =
                String.format(
                        """
            {
              "customerId": "%s",
              "shippingAddress": {"line1": "1", "city": "X", "postalCode": "1", "country": "US"},
              "lines": [{"productId": "%s", "quantity": 1}]
            }
            """,
                        customerId, productId);

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(400);
    }

    @Test
    void createReturns502WhenProductServiceIsDown() {
        String productId = "00000000-0000-0000-0000-000000000201";
        String customerId = "00000000-0000-0000-0000-000000000101";

        WireMockDownstreamConfig.stubProductServerError(productId);
        WireMockDownstreamConfig.stubCustomer(customerId, "alice@omp.local", "Alice", "Customer");

        String body =
                String.format(
                        """
            {
              "customerId": "%s",
              "shippingAddress": {"line1": "1", "city": "X", "postalCode": "1", "country": "US"},
              "lines": [{"productId": "%s", "quantity": 1}]
            }
            """,
                        customerId, productId);

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(502);
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    @Test
    void placeTransitionsOrderToPlaced() {
        // Order 303 is DRAFT (Alice's cart)
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .post("/api/v1/orders/{id}/place", "00000000-0000-0000-0000-000000000303")
                .then()
                .statusCode(200)
                .body("status", equalTo("PLACED"));
    }

    @Test
    void confirmRequiresAdmin() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/orders/{id}/confirm", "00000000-0000-0000-0000-000000000302")
                .then()
                .statusCode(403);
    }

    @Test
    void confirmByAdminTransitionsToConfirmed() {
        String id = createDraft();
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .post("/api/v1/orders/" + id + "/place")
                .then()
                .statusCode(200);
        String paymentId =
                jdbc.queryForObject(
                                "select payment_id from orders.purchase_order where id = ?",
                                java.util.UUID.class,
                                java.util.UUID.fromString(id))
                        .toString();
        payments.confirm(paymentId, "pi-confirmed-order-test");
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\": \"payment captured\"}")
                .when()
                .post("/api/v1/orders/{id}/confirm", id)
                .then()
                .statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }
}

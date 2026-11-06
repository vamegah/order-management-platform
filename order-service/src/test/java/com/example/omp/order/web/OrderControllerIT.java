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

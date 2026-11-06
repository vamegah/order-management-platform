package com.example.omp.order.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** Consumer-facing order responses and generated shipping contract; requires PostgreSQL. */
@Import(TestSecurityConfig.class)
class OrderOpenApiContractIT extends AbstractIntegrationTest {
    @Test
    void singleOrderContainsItsStablePublicFields() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get("/api/v1/orders/{id}", "00000000-0000-0000-0000-000000000301")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("id", equalTo("00000000-0000-0000-0000-000000000301"))
                .body("orderNumber", startsWith("ORD-"))
                .body("status", equalTo("DELIVERED"))
                .body("total.currency", equalTo("USD"))
                .body("lines", not(empty()))
                .body("statusHistory", not(empty()));
    }

    @Test
    void missingOrderUsesProblemDetails() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get("/api/v1/orders/{id}", "00000000-0000-0000-0000-000000000999")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("type", notNullValue())
                .body("title", not(emptyString()));
    }

    @Test
    void openApiDescribesPendingShipmentAndThePublicStatusEnum() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get("/v3/api-docs")
                .then()
                .statusCode(200)
                .body(
                        "paths.'/api/v1/orders/{id}/ship'.post.responses",
                        allOf(hasKey("200"), hasKey("202")))
                .body("components.schemas.OrderDto.properties.status.enum", hasItem("FULFILLING"));
    }
}

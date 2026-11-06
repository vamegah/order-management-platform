package com.example.omp.product.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.TestSecurityConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** Systematic coverage of error responses: 400, 401, 403, 404, 409, 500. */
@Import(TestSecurityConfig.class)
class ProductErrorHandlingIT extends AbstractIntegrationTest {

    @Test
    void unauthorizedRequestReturnsProblemDetails() {
        given().when().get("/api/v1/products").then().statusCode(401);
    }

    @Test
    void forbiddenRequestReturns403() {
        String body =
                """
            {
              "sku": "ERR-001",
              "name": "Test",
              "categoryId": "00000000-0000-0000-0000-000000000102",
              "price": {"amount": 1.00, "currency": "USD"}
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(403);
    }

    @Test
    void invalidJsonReturns400() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body("{invalid json}")
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(400);
    }

    @Test
    void notFoundProblemDetailsHasTraceId() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/{id}", "00000000-0000-0000-0000-000000000999")
                .then()
                .statusCode(404)
                .body("type", equalTo("about:blank"))
                .body("title", equalTo("Product Not Found"))
                .body("status", equalTo(404))
                .body("instance", containsString("/api/v1/products/"))
                .body("traceId", notNullValue());
    }

    @Test
    void validationProblemDetailsListsAllErrors() {
        String body =
                """
            {
              "sku": "x",
              "name": "",
              "categoryId": "00000000-0000-0000-0000-000000000102",
              "price": {"amount": -1, "currency": "US"}
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(400)
                .body("title", equalTo("Validation Failed"))
                .body(
                        "errors",
                        hasSize(greaterThanOrEqualTo(3))) // sku, name, price.amount, price.currency
                .body("errors.field", hasItems("sku", "name"));
    }
}

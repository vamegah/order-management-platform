package com.example.omp.product.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.TestSecurityConfig;
import io.restassured.module.jsv.JsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * Contract tests. Validate that the responses produced by the service conform to JSON schemas
 * derived from the OpenAPI spec.
 *
 * <p>Approach: load the schema from a test resource (extracted from the generated OpenAPI spec
 * during build) and validate the response body against it.
 *
 * <p>In a future iteration, these schemas could be auto-extracted from the live {@code
 * /v3/api-docs} endpoint.
 */
@Import(TestSecurityConfig.class)
class OpenApiContractIT extends AbstractIntegrationTest {

    @Test
    void listResponseMatchesProductListSchema() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products?size=5")
                .then()
                .statusCode(200)
                .body(
                        JsonSchemaValidator.matchesJsonSchemaInClasspath(
                                "schemas/product-list-response.json"));
    }

    @Test
    void singleProductMatchesProductSchema() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/{id}", "00000000-0000-0000-0000-000000000201")
                .then()
                .statusCode(200)
                .body(
                        JsonSchemaValidator.matchesJsonSchemaInClasspath(
                                "schemas/product-response.json"));
    }

    @Test
    void problemDetailsMatchesRfc7807Schema() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/{id}", "00000000-0000-0000-0000-000000000999")
                .then()
                .statusCode(404)
                .body(
                        JsonSchemaValidator.matchesJsonSchemaInClasspath(
                                "schemas/problem-details.json"));
    }
}

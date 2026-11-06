package com.example.omp.product.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.TestSecurityConfig;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Consumer HTTP checks using the production filter chain, service and migrated database. */
class ProductBoundaryIT extends AbstractIntegrationTest {
    private static final String FIRST = "00000000-0000-0000-0000-000000000201";
    private static final String SECOND = "00000000-0000-0000-0000-000000000202";
    private static final String MISSING = "00000000-0000-0000-0000-000000000999";

    @Test
    void batchPreservesRequestOrderAndOmitsMissingAndDuplicateIds() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("ids", SECOND + "," + MISSING + "," + FIRST + "," + SECOND)
                .get("/api/v1/products/batch")
                .then()
                .statusCode(200)
                .body("id", contains(SECOND, FIRST));
    }

    @Test
    void batchRejectsOversizedRequest() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("ids", String.join(",", Collections.nCopies(101, FIRST)))
                .get("/api/v1/products/batch")
                .then()
                .statusCode(400);
    }

    @Test
    void batchRejectsMalformedId() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("ids", "invalid")
                .get("/api/v1/products/batch")
                .then()
                .statusCode(400);
    }

    @Test
    void invalidBearerIsUnauthorized() {
        given().auth()
                .oauth2("unknown-token")
                .get("/api/v1/products/batch?ids=" + FIRST)
                .then()
                .statusCode(401);
    }

    @Test
    void configuredSortFieldIsAccepted() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("sort", "name,asc")
                .get("/api/v1/products")
                .then()
                .statusCode(200)
                .body("content", not(empty()));
    }

    @Test
    void unknownSortFieldIsRejected() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("sort", "unknownField,asc")
                .get("/api/v1/products")
                .then()
                .statusCode(400);
    }

    @Test
    void allowedBrowserCanPreflightWithoutCredentials() {
        given().header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .options("/api/v1/products")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "http://localhost:3000");
    }

    @Test
    void unconfiguredBrowserOriginIsDenied() {
        given().header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET")
                .options("/api/v1/products")
                .then()
                .statusCode(403)
                .header("Access-Control-Allow-Origin", nullValue());
    }
}

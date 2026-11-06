package com.example.omp.customer.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.customer.AbstractIntegrationTest;
import com.example.omp.customer.TestSecurityConfig;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Ownership and batch contracts exercised through real HTTP and PostgreSQL. */
class CustomerBoundaryIT extends AbstractIntegrationTest {
    private static final String ALICE = "00000000-0000-0000-0000-000000000101";
    private static final String BOB = "00000000-0000-0000-0000-000000000102";
    private static final String MISSING = "00000000-0000-0000-0000-000000000999";

    @Test
    void adminBatchPreservesOrderAndOmitsMissingAndDuplicateIds() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("ids", BOB + "," + MISSING + "," + ALICE + "," + BOB)
                .get("/api/v1/customers/batch")
                .then()
                .statusCode(200)
                .body("id", contains(BOB, ALICE));
    }

    @Test
    void verifiedCustomerCanReadOwnBatchAndProfile() {
        given().auth()
                .oauth2(TestSecurityConfig.linkedAliceToken())
                .queryParam("ids", ALICE)
                .get("/api/v1/customers/batch")
                .then()
                .statusCode(200)
                .body("id", contains(ALICE));
        given().auth()
                .oauth2(TestSecurityConfig.linkedAliceToken())
                .get("/api/v1/customers/" + ALICE)
                .then()
                .statusCode(200)
                .body("id", equalTo(ALICE));
    }

    @Test
    void verifiedCustomerCannotReadAnotherBatchOrProfile() {
        given().auth()
                .oauth2(TestSecurityConfig.linkedAliceToken())
                .queryParam("ids", ALICE + "," + BOB)
                .get("/api/v1/customers/batch")
                .then()
                .statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.linkedAliceToken())
                .get("/api/v1/customers/" + BOB)
                .then()
                .statusCode(403);
    }

    @Test
    void verifiedCustomerCannotReadAnotherCustomersAddresses() {
        given().auth()
                .oauth2(TestSecurityConfig.linkedAliceToken())
                .get("/api/v1/customers/" + BOB + "/addresses")
                .then()
                .statusCode(403);
    }

    @Test
    void customerWithoutVerifiedIdCannotReadAddressCollection() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .get("/api/v1/customers/" + ALICE + "/addresses")
                .then()
                .statusCode(403);
    }

    @Test
    void adminBatchRejectsOversizedRequest() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("ids", String.join(",", Collections.nCopies(101, ALICE)))
                .get("/api/v1/customers/batch")
                .then()
                .statusCode(400);
    }

    @Test
    void invalidBearerIsUnauthorized() {
        given().auth().oauth2("unknown-token").get("/api/v1/customers/me").then().statusCode(401);
    }

    @Test
    void configuredSortFieldIsAccepted() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("sort", "email,asc")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("content", not(empty()));
    }

    @Test
    void customerNameSortingUsesPersistedFields() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("sort", "firstName,asc")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("content", not(empty()));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("sort", "lastName,desc")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("content", not(empty()));
    }

    @Test
    void unknownSortFieldIsRejected() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("sort", "name,asc")
                .get("/api/v1/customers")
                .then()
                .statusCode(400);
    }

    @Test
    void allowedBrowserCanPreflightWithoutCredentials() {
        given().header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .options("/api/v1/customers/me")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "http://localhost:3000");
    }

    @Test
    void unconfiguredBrowserOriginIsDenied() {
        given().header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET")
                .options("/api/v1/customers/me")
                .then()
                .statusCode(403)
                .header("Access-Control-Allow-Origin", nullValue());
    }
}

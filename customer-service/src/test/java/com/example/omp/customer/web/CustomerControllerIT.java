package com.example.omp.customer.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.customer.AbstractIntegrationTest;
import com.example.omp.customer.TestSecurityConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(TestSecurityConfig.class)
class CustomerControllerIT extends AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // GET /customers
    // -------------------------------------------------------------------------

    @Test
    void listRequiresAdmin() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/customers")
                .then()
                .statusCode(403);
    }

    @Test
    void listReturnsSeedCustomersAsAdmin() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("size", 50)
                .when()
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("content", not(empty()))
                .body("totalElements", greaterThanOrEqualTo(5))
                .body("content[0].email", not(emptyString()));
    }

    @Test
    void listFiltersByStatus() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .queryParam("status", "ACTIVE")
                .queryParam("size", 50)
                .when()
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("content.status", everyItem(equalTo("ACTIVE")));
    }

    // -------------------------------------------------------------------------
    // GET /customers/me
    // -------------------------------------------------------------------------

    @Test
    void meReturnsCustomerLinkedToJwtSub() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/customers/me")
                .then()
                .statusCode(200)
                .body("email", equalTo("alice@omp.local"))
                .body("firstName", equalTo("Alice"))
                .body("keycloakSub", equalTo("test-alice"))
                .body("addresses", hasSize(2))
                .body("preferences.locale", equalTo("en-US"));
    }

    @Test
    void meReturns404ForUnlinkedUser() {
        given().auth()
                .oauth2(TestSecurityConfig.unlinkedToken())
                .when()
                .get("/api/v1/customers/me")
                .then()
                .statusCode(404)
                .body("title", equalTo("Customer Not Found"));
    }

    // -------------------------------------------------------------------------
    // GET /customers/{id}
    // -------------------------------------------------------------------------

    @Test
    void getByIdAsAdminReturnsFullProfile() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .get("/api/v1/customers/{id}", "00000000-0000-0000-0000-000000000101")
                .then()
                .statusCode(200)
                .body("id", equalTo("00000000-0000-0000-0000-000000000101"))
                .body("email", equalTo("alice@omp.local"))
                .body("addresses", hasSize(2))
                .body("preferences", notNullValue());
    }

    @Test
    void getByIdAsCustomerReturns403() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/customers/{id}", "00000000-0000-0000-0000-000000000101")
                .then()
                .statusCode(403);
    }

    @Test
    void getByIdReturns404ForUnknown() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .get("/api/v1/customers/{id}", "00000000-0000-0000-0000-000000000999")
                .then()
                .statusCode(404)
                .body("title", equalTo("Customer Not Found"));
    }

    // -------------------------------------------------------------------------
    // POST /customers
    // -------------------------------------------------------------------------

    @Test
    void createAsAdminReturns201() {
        String body =
                """
            {
              "email": "newcustomer@example.com",
              "firstName": "New",
              "lastName": "Customer",
              "phone": "+1-555-0199"
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/customers")
                .then()
                .statusCode(201)
                .header("Location", containsString("/api/v1/customers/"))
                .body("email", equalTo("newcustomer@example.com"))
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void createReturns409ForDuplicateEmail() {
        String body =
                """
            {
              "email": "alice@omp.local",
              "firstName": "Duplicate",
              "lastName": "Attempt"
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/customers")
                .then()
                .statusCode(409)
                .body("title", equalTo("Conflict"));
    }

    @Test
    void createReturns400ForInvalidEmail() {
        String body =
                """
            {
              "email": "not-an-email",
              "firstName": "Bad",
              "lastName": "Email"
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/customers")
                .then()
                .statusCode(400)
                .body("title", equalTo("Validation Failed"))
                .body("errors", not(empty()));
    }

    // -------------------------------------------------------------------------
    // DELETE /customers/{id}
    // -------------------------------------------------------------------------

    @Test
    void deactivateAsAdminReturns204() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .delete("/api/v1/customers/{id}", "00000000-0000-0000-0000-000000000102")
                .then()
                .statusCode(204);
    }
}

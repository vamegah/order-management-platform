package com.example.omp.customer.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.customer.AbstractIntegrationTest;
import com.example.omp.customer.TestSecurityConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(TestSecurityConfig.class)
class AddressControllerIT extends AbstractIntegrationTest {

    @Test
    void listAddressesAsCustomer() {
        given().auth()
                .oauth2(TestSecurityConfig.linkedAliceToken())
                .when()
                .get("/api/v1/customers/{id}/addresses", "00000000-0000-0000-0000-000000000101")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("addressType", containsInAnyOrder("BILLING", "SHIPPING"));
    }

    @Test
    void listAddressesForCustomerWithoutAddressReturnsEmpty() {
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .get("/api/v1/customers/{id}/addresses", "00000000-0000-0000-0000-000000000105")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void addAddressAsAdmin() {
        String body =
                """
            {
              "addressType": "SHIPPING",
              "line1": "500 New St",
              "city": "Portland",
              "state": "OR",
              "postalCode": "97201",
              "country": "US"
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/customers/{id}/addresses", "00000000-0000-0000-0000-000000000102")
                .then()
                .statusCode(201)
                .body("line1", equalTo("500 New St"))
                .body("addressType", equalTo("SHIPPING"));
    }
}

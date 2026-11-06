package com.example.omp.product.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.TestSecurityConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * Integration tests for {@link ProductController}. Full Spring context, real Postgres (via
 * Testcontainers), mocked JWT authentication.
 */
@Import(TestSecurityConfig.class)
class ProductControllerIT extends AbstractIntegrationTest {

    private String createMutableProduct(String sku) {
        return given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(
                        java.util.Map.of(
                                "sku",
                                sku,
                                "name",
                                "Mutation fixture",
                                "categoryId",
                                "00000000-0000-0000-0000-000000000102",
                                "price",
                                java.util.Map.of("amount", "19.99", "currency", "USD")))
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/products
    // -------------------------------------------------------------------------

    @Test
    void listRequiresAuthentication() {
        given().when().get("/api/v1/products").then().statusCode(401);
    }

    @Test
    void listReturnsSeedProductsForAlice() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products?size=50")
                .then()
                .statusCode(200)
                .body("content", not(empty()))
                .body("totalElements", greaterThanOrEqualTo(4))
                .body("content.status", everyItem(equalTo("ACTIVE")))
                .body("content[0].sku", not(emptyString()))
                .body("content[0].price.currency", not(emptyString()));
    }

    @Test
    void listRejectsOversizedPage() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products?size=1000")
                .then()
                .statusCode(400)
                .body("detail", containsString("must be <= 100"));
    }

    @Test
    void listFiltersByCategorySlug() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("categorySlug", "laptops")
                .queryParam("size", 50)
                .when()
                .get("/api/v1/products")
                .then()
                .statusCode(200)
                .body("content.category.slug", everyItem(equalTo("laptops")));
    }

    @Test
    void listFiltersByStatus() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .queryParam("status", "DISCONTINUED")
                .queryParam("size", 50)
                .when()
                .get("/api/v1/products")
                .then()
                .statusCode(200)
                .body("content.status", everyItem(equalTo("DISCONTINUED")));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/products/{id}
    // -------------------------------------------------------------------------

    @Test
    void getByIdReturns404ForUnknownId() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/{id}", "00000000-0000-0000-0000-000000000999")
                .then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("title", equalTo("Product Not Found"))
                .body("traceId", notNullValue());
    }

    @Test
    void getByIdReturnsSeedProduct() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/{id}", "00000000-0000-0000-0000-000000000201")
                .then()
                .statusCode(200)
                .body("sku", equalTo("LAPTOP-PRO-14"))
                .body("price.amount", equalTo(2499.99f));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/products/sku/{sku}
    // -------------------------------------------------------------------------

    @Test
    void getBySkuIsCaseInsensitive() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/sku/{sku}", "laptop-pro-14")
                .then()
                .statusCode(200)
                .body("sku", equalTo("LAPTOP-PRO-14"));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/products
    // -------------------------------------------------------------------------

    @Test
    void createRequiresAdminRole() {
        String body =
                """
            {
              "sku": "NEW-ALICE-001",
              "name": "Alice's Product",
              "categoryId": "00000000-0000-0000-0000-000000000102",
              "price": {"amount": 9.99, "currency": "USD"}
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
    void createReturns201WithLocationHeader() {
        String body =
                """
            {
              "sku": "NEW-ADMIN-001",
              "name": "Admin's Product",
              "categoryId": "00000000-0000-0000-0000-000000000102",
              "price": {"amount": 19.99, "currency": "USD"}
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .header("Location", containsString("/api/v1/products/"))
                .body("sku", equalTo("NEW-ADMIN-001"))
                .body("id", not(emptyString()));
    }

    @Test
    void createReturns409ForDuplicateSku() {
        String body =
                """
            {
              "sku": "LAPTOP-PRO-14",
              "name": "Duplicate",
              "categoryId": "00000000-0000-0000-0000-000000000102",
              "price": {"amount": 1.00, "currency": "USD"}
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(409)
                .body("title", equalTo("Conflict"))
                .body("detail", containsString("LAPTOP-PRO-14"));
    }

    @Test
    void createReturns400ForInvalidBody() {
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
                .body("errors", not(empty()))
                .body("traceId", notNullValue());
    }

    // -------------------------------------------------------------------------
    // PUT /api/v1/products/{id}
    // -------------------------------------------------------------------------

    @Test
    void updateChangesMutableFields() {
        String id = createMutableProduct("UPDATE-FIXTURE");
        String body =
                """
            {
              "name": "Updated Name",
              "description": "Updated description",
              "categoryId": "00000000-0000-0000-0000-000000000102",
              "price": {"amount": 3499.99, "currency": "USD"},
              "images": []
            }
            """;

        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .put("/api/v1/products/{id}", id)
                .then()
                .statusCode(200)
                .body("name", equalTo("Updated Name"))
                .body("price.amount", equalTo(3499.99f))
                .body("sku", equalTo("UPDATE-FIXTURE")); // SKU unchanged
    }

    // -------------------------------------------------------------------------
    // DELETE /api/v1/products/{id}
    // -------------------------------------------------------------------------

    @Test
    void deactivateReturns204AndMarksInactive() {
        String id = createMutableProduct("DELETE-FIXTURE");
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .when()
                .delete("/api/v1/products/{id}", id)
                .then()
                .statusCode(204);

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/products/{id}", id)
                .then()
                .statusCode(200)
                .body("status", equalTo("INACTIVE"));
    }
}

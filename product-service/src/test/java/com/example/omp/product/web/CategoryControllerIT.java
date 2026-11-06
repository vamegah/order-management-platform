package com.example.omp.product.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** Integration tests for {@link CategoryController}. */
@Import(TestSecurityConfig.class)
class CategoryControllerIT extends AbstractIntegrationTest {

    @Test
    void listReturnsAllSeedCategories() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/categories")
                .then()
                .statusCode(200)
                .body("$", hasSize(3))
                .body("slug", containsInAnyOrder("electronics", "laptops", "phones"));
    }

    @Test
    void getBySlugReturnsCategory() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/categories/{slug}", "laptops")
                .then()
                .statusCode(200)
                .body("name", equalTo("Laptops"))
                .body("parent.slug", equalTo("electronics"));
    }

    @Test
    void getBySlugReturns404ForUnknown() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .when()
                .get("/api/v1/categories/{slug}", "does-not-exist")
                .then()
                .statusCode(404)
                .body("title", equalTo("Category Not Found"));
    }

    @Test
    void listRequiresAuthentication() {
        given().when().get("/api/v1/categories").then().statusCode(401);
    }
}

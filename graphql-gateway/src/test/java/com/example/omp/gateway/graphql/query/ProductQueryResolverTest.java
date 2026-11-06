package com.example.omp.gateway.graphql.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.gateway.AbstractGraphQlTest;
import com.example.omp.gateway.TestSecurityConfig;
import com.example.omp.gateway.WireMockDownstreamConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ActiveProfiles;

/**
 * Unit tests for {@link ProductQueryResolver}.
 *
 * <p>Uses the {@code @GraphQlTest} slice — loads the GraphQL infrastructure and this resolver only.
 * Downstream calls are stubbed via WireMock.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class ProductQueryResolverTest extends AbstractGraphQlTest {

    @Autowired private GraphQlTester graphQlTester;

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String PRODUCT_SKU = "LAPTOP-PRO-14";

    @BeforeEach
    void reset() {
        resetWireMocks();
    }

    // =========================================================================
    // product(id) — happy path
    // =========================================================================

    @Test
    void productReturnsStubbedProduct() {
        WireMockDownstreamConfig.stubProduct(
                PRODUCT_ID, PRODUCT_SKU, "Pro Laptop", "2499.99", "USD");

        graphQlTester
                .document(
                        """
                query Product($id: ID!) {
                  product(id: $id) {
                    id
                    sku
                    name
                    price { amount currency }
                    status
                  }
                }
                """)
                .variable("id", PRODUCT_ID.toString())
                .execute()
                .path("product.id")
                .entity(String.class)
                .isEqualTo(PRODUCT_ID.toString())
                .path("product.sku")
                .entity(String.class)
                .isEqualTo(PRODUCT_SKU)
                .path("product.name")
                .entity(String.class)
                .isEqualTo("Pro Laptop")
                .path("product.price.amount")
                .entity(String.class)
                .isEqualTo("2499.99")
                .path("product.price.currency")
                .entity(String.class)
                .isEqualTo("USD")
                .path("product.status")
                .entity(String.class)
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // product(id) — error paths
    // =========================================================================

    @Test
    void productReturnsErrorWhenNotFound() {
        WireMockDownstreamConfig.stubProductNotFound(PRODUCT_ID);

        graphQlTester
                .document(
                        """
                query Product($id: ID!) {
                  product(id: $id) { id }
                }
                """)
                .variable("id", PRODUCT_ID.toString())
                .execute()
                .errors()
                .satisfy(
                        errors -> {
                            assertThat(errors).hasSize(1);
                            assertThat(errors.get(0).getExtensions())
                                    .containsEntry("code", "NOT_FOUND");
                        })
                .path("product")
                .valueIsNull();
    }

    @Test
    void productReturnsErrorWhenDownstreamFails() {
        WireMockDownstreamConfig.stubProductServerError(PRODUCT_ID);

        graphQlTester
                .document(
                        """
                query Product($id: ID!) {
                  product(id: $id) { id }
                }
                """)
                .variable("id", PRODUCT_ID.toString())
                .execute()
                .errors()
                .satisfy(
                        errors -> {
                            assertThat(errors).hasSize(1);
                            assertThat(errors.get(0).getExtensions())
                                    .containsEntry("code", "DOWNSTREAM_SERVICE_ERROR");
                        })
                .path("product")
                .valueIsNull();
    }

    // =========================================================================
    // productBySku
    // =========================================================================

    @Test
    void productBySkuReturnsMatchingProduct() {
        WireMockDownstreamConfig.stubProduct(
                PRODUCT_ID, PRODUCT_SKU, "Pro Laptop", "2499.99", "USD");
        AbstractGraphQlTest.productWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/sku/" + PRODUCT_SKU))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "id": "%s", "sku": "%s", "name": "Pro Laptop",
                          "price": {"amount": 2499.99, "currency": "USD"},
                          "status": "ACTIVE", "images": [],
                          "createdAt": "2025-01-01T00:00:00Z",
                          "updatedAt": "2025-01-01T00:00:00Z"
                        }
                        """
                                                        .formatted(PRODUCT_ID, PRODUCT_SKU))));

        graphQlTester
                .document(
                        """
                query ProductBySku($sku: String!) {
                  productBySku(sku: $sku) { sku }
                }
                """)
                .variable("sku", PRODUCT_SKU)
                .execute()
                .path("productBySku.sku")
                .entity(String.class)
                .isEqualTo(PRODUCT_SKU);
    }

    // =========================================================================
    // products — list
    // =========================================================================

    @Test
    void productsReturnsPaginatedList() {
        WireMockDownstreamConfig.stubProductList(
                """
            {
              "content": [
                {
                  "id": "00000000-0000-0000-0000-000000000201",
                  "sku": "LAPTOP-PRO-14", "name": "Pro Laptop",
                  "price": {"amount": 2499.99, "currency": "USD"},
                  "status": "ACTIVE", "images": [],
                  "createdAt": "2025-01-01T00:00:00Z",
                  "updatedAt": "2025-01-01T00:00:00Z"
                },
                {
                  "id": "00000000-0000-0000-0000-000000000202",
                  "sku": "LAPTOP-AIR-13", "name": "Air Laptop",
                  "price": {"amount": 1299.00, "currency": "USD"},
                  "status": "ACTIVE", "images": [],
                  "createdAt": "2025-01-01T00:00:00Z",
                  "updatedAt": "2025-01-01T00:00:00Z"
                }
              ],
              "page": 0, "size": 2, "totalElements": 2, "totalPages": 1,
              "first": true, "last": true
            }
            """);

        graphQlTester
                .document(
                        """
                query Products($page: Int, $size: Int) {
                  products(page: $page, size: $size) {
                    content { sku }
                    totalElements
                    first
                    last
                  }
                }
                """)
                .variable("page", 0)
                .variable("size", 2)
                .execute()
                .path("products.content")
                .entityList(java.util.Map.class)
                .hasSize(2)
                .path("products.totalElements")
                .entity(Integer.class)
                .isEqualTo(2)
                .path("products.first")
                .entity(Boolean.class)
                .isEqualTo(true)
                .path("products.last")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    // =========================================================================
    // categories
    // =========================================================================

    @Test
    void categoriesReturnsList() {
        WireMockDownstreamConfig.stubCategories(
                """
            [
              {"id": "00000000-0000-0000-0000-000000000101", "name": "Electronics",
               "slug": "electronics", "sortOrder": 10},
              {"id": "00000000-0000-0000-0000-000000000102", "name": "Laptops",
               "slug": "laptops", "sortOrder": 10}
            ]
            """);

        graphQlTester
                .document("{ categories { slug name } }")
                .execute()
                .path("categories")
                .entityList(java.util.Map.class)
                .hasSize(2)
                .path("categories[0].slug")
                .entity(String.class)
                .isEqualTo("electronics");
    }
}

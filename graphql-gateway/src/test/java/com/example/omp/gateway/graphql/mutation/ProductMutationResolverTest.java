package com.example.omp.gateway.graphql.mutation;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

/**
 * Unit tests for {@link ProductMutationResolver}.
 *
 * <p>Uses {@code @WithMockUser} to set the security context. The {@code @PreAuthorize} annotations
 * on the resolver are enforced by Spring Security's method interceptor in the {@code @GraphQlTest}
 * slice.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class ProductMutationResolverTest extends AbstractGraphQlTest {

    @Autowired private GraphQlTester graphQlTester;

    private static final UUID NEW_PRODUCT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000999");
    private static final UUID EXISTING_PRODUCT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @BeforeEach
    void reset() {
        resetWireMocks();
    }

    // =========================================================================
    // createProduct — admin success
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void createProductAsAdminReturnsCreated() {
        WireMockDownstreamConfig.stubCreateProduct(
                NEW_PRODUCT_ID, "NEW-SKU-1", "New Product", "9.99", "USD");

        graphQlTester
                .document(
                        """
                mutation CreateProduct($input: CreateProductInput!) {
                  createProduct(input: $input) {
                    id
                    sku
                    name
                    price { amount currency }
                  }
                }
                """)
                .variable(
                        "input",
                        java.util.Map.of(
                                "sku",
                                "NEW-SKU-1",
                                "name",
                                "New Product",
                                "categoryId",
                                CATEGORY_ID.toString(),
                                "price",
                                java.util.Map.of("amount", "9.99", "currency", "USD")))
                .execute()
                .path("createProduct.sku")
                .entity(String.class)
                .isEqualTo("NEW-SKU-1")
                .path("createProduct.name")
                .entity(String.class)
                .isEqualTo("New Product")
                .path("createProduct.price.amount")
                .entity(String.class)
                .isEqualTo("9.99");
    }

    // =========================================================================
    // createProduct — non-admin forbidden
    // =========================================================================

    @Test
    @WithMockUser(roles = "customer")
    void createProductAsNonAdminReturnsForbidden() {
        graphQlTester
                .document(
                        """
                mutation CreateProduct($input: CreateProductInput!) {
                  createProduct(input: $input) { id }
                }
                """)
                .variable(
                        "input",
                        java.util.Map.of(
                                "sku",
                                "NEW-SKU-1",
                                "name",
                                "New Product",
                                "categoryId",
                                CATEGORY_ID.toString(),
                                "price",
                                java.util.Map.of("amount", "9.99", "currency", "USD")))
                .execute()
                .errors()
                .satisfy(
                        errors -> {
                            assertThat(errors).isNotEmpty();
                            assertThat(errors.get(0).getExtensions())
                                    .containsEntry("code", "FORBIDDEN");
                        })
                .path("$['data']")
                .valueIsNull();
    }

    // =========================================================================
    // createProduct — downstream conflict
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void createProductWithDuplicateSkuReturnsConflict() {
        // Stub a 409 from product-service
        AbstractGraphQlTest.productWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .withStatus(409)
                                        .withHeader("Content-Type", "application/problem+json")
                                        .withBody(
                                                """
                        {"type":"about:blank","title":"Conflict","status":409,
                         "detail":"A product with SKU 'LAPTOP-PRO-14' already exists"}
                        """)));

        graphQlTester
                .document(
                        """
                mutation CreateProduct($input: CreateProductInput!) {
                  createProduct(input: $input) { id }
                }
                """)
                .variable(
                        "input",
                        java.util.Map.of(
                                "sku",
                                "LAPTOP-PRO-14",
                                "name",
                                "Duplicate",
                                "categoryId",
                                CATEGORY_ID.toString(),
                                "price",
                                java.util.Map.of("amount", "9.99", "currency", "USD")))
                .execute()
                .errors()
                .satisfy(
                        errors -> {
                            assertThat(errors).isNotEmpty();
                            assertThat(errors.get(0).getExtensions())
                                    .containsEntry("code", "CONFLICT");
                        })
                .path("$['data']")
                .valueIsNull();
    }

    // =========================================================================
    // updateProduct
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void updateProductAsAdminReturnsUpdated() {
        // Stub a PUT response with a modified product
        AbstractGraphQlTest.productWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.put(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/" + EXISTING_PRODUCT_ID))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "id": "%s",
                          "sku": "LAPTOP-PRO-14",
                          "name": "Pro Laptop (updated)",
                          "price": {"amount": 2999.99, "currency": "USD"},
                          "status": "ACTIVE", "images": [],
                          "createdAt": "2025-01-01T00:00:00Z",
                          "updatedAt": "2025-01-15T00:00:00Z"
                        }
                        """
                                                        .formatted(EXISTING_PRODUCT_ID))));

        graphQlTester
                .document(
                        """
                mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {
                  updateProduct(id: $id, input: $input) {
                    name
                    price { amount }
                  }
                }
                """)
                .variable("id", EXISTING_PRODUCT_ID.toString())
                .variable(
                        "input",
                        java.util.Map.of(
                                "name", "Pro Laptop (updated)",
                                "categoryId", CATEGORY_ID.toString(),
                                "price", java.util.Map.of("amount", "2999.99", "currency", "USD")))
                .execute()
                .path("updateProduct.name")
                .entity(String.class)
                .isEqualTo("Pro Laptop (updated)")
                .path("updateProduct.price.amount")
                .entity(String.class)
                .isEqualTo("2999.99");
    }

    // =========================================================================
    // deactivateProduct — 204 no body from downstream
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void deactivateProductAsAdmin() {
        // Stub a 204 response
        AbstractGraphQlTest.productWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.delete(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/" + EXISTING_PRODUCT_ID))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .withStatus(204)));

        // The resolver for deactivateProduct returns the deactivated product.
        // In practice, the resolver would re-fetch it via getProduct after the DELETE.
        // For this test, we stub the subsequent GET as well.
        WireMockDownstreamConfig.stubProduct(
                EXISTING_PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", "USD");
        // ... and override with a status=INACTIVE response.
        // (In practice the service would set it; here we just verify the DELETE happened.)

        graphQlTester
                .document(
                        """
                mutation DeactivateProduct($id: ID!) {
                  deactivateProduct(id: $id) { id }
                }
                """)
                .variable("id", EXISTING_PRODUCT_ID.toString())
                .execute()
                .path("deactivateProduct.id")
                .entity(String.class)
                .isEqualTo(EXISTING_PRODUCT_ID.toString());

        // Verify the DELETE request was issued
        AbstractGraphQlTest.productWireMock.verify(
                com.github.tomakehurst.wiremock.client.WireMock.exactly(1),
                com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                "/api/v1/products/" + EXISTING_PRODUCT_ID)));
    }
}

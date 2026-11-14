package com.example.omp.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.example.omp.gateway.WireMockDownstreamConfig.OrderLineStub;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end HTTP tests for the {@code /graphql} endpoint.
 *
 * <p>Loads the FULL Spring context (unlike the {@code @GraphQlTest} slice used for resolver unit
 * tests). This is the only test class that exercises:
 *
 * <ul>
 *   <li>The Spring Security filter chain (401 for missing token).
 *   <li>HTTP-level concerns (content type, status code).
 *   <li>The full Feign client stack (WireMock-stubbed downstreams).
 * </ul>
 *
 * <p>Downstream services are stubbed via the three WireMock servers from {@link
 * AbstractGraphQlTest}. The gateway's Feign clients point at those servers (via
 * {@code @DynamicPropertySource}).
 *
 * <p><b>Note on HTTP status codes:</b> GraphQL always returns HTTP 200 â€” even for business errors
 * like NOT_FOUND or FORBIDDEN. Errors appear in the response body's {@code errors} array. Only auth
 * failures (missing/invalid JWT) return 401, because they're rejected before GraphQL execution
 * begins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class GraphQlEndpointIT extends AbstractGraphQlTest {

    @LocalServerPort int port;

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @BeforeEach
    void resetStubs() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        resetWireMocks();
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    @Test
    void requestWithoutTokenReturns401() {
        given().contentType(ContentType.JSON)
                .body("{\"query\":\"{ products { totalElements } }\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(401);
    }

    @Test
    void requestWithInvalidTokenReturns401() {
        given().auth()
                .oauth2("not-a-real-token")
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ products { totalElements } }\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(401);
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    void authenticatedQueryReturnsData() {
        WireMockDownstreamConfig.stubProduct(
                PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", "USD");

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(
                        String.format(
                                """
                {"query":"query { product(id: \\"%s\\") { sku name } }"}
                """,
                                PRODUCT_ID))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("data.product.sku", equalTo("LAPTOP-PRO-14"))
                .body("data.product.name", equalTo("Pro Laptop"))
                .body("errors", nullValue());
    }

    // =========================================================================
    // Authorization â€” GraphQL returns 200 with errors[]
    // =========================================================================

    @Test
    void adminMutationAsCustomerReturnsForbidden() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(
                        """
                {"query":"mutation { createCustomer(input: { email: \\"x@y.z\\", firstName: \\"X\\", lastName: \\"Y\\" }) { id } }"}
                """)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200) // NOT 403 â€” GraphQL semantics
                .body("data.createCustomer", nullValue())
                .body("errors", hasSize(1))
                .body("errors[0].extensions.code", equalTo("FORBIDDEN"));
    }

    @Test
    void adminCanCreateCustomerAndForwardsItsBearerToken() {
        WireMockDownstreamConfig.stubCreateCustomer(
                CUSTOMER_ID, "new@example.com", "New", "Customer");
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "query",
                                "mutation { createCustomer(input: { email: \"new@example.com\", firstName: \"New\", lastName: \"Customer\" }) { id email } }"))
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.createCustomer.id", equalTo(CUSTOMER_ID.toString()))
                .body("data.createCustomer.email", equalTo("new@example.com"))
                .body("errors", nullValue());
        customerWireMock.verify(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(
                                        "/api/v1/customers"))
                        .withHeader(
                                "Authorization",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                        "Bearer " + TestSecurityConfig.adminToken())));
    }

    @Test
    void duplicateCustomerCreationReturnsAConflictThroughGraphql() {
        WireMockDownstreamConfig.stubCreateCustomerConflict("Email already exists");
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "query",
                                "mutation { createCustomer(input: { email: \"existing@example.com\", firstName: \"Existing\", lastName: \"Customer\" }) { id } }"))
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.createCustomer", nullValue())
                .body("errors[0].extensions.code", equalTo("CONFLICT"));
    }

    // =========================================================================
    // Downstream error propagation
    // =========================================================================

    @Test
    void productNotFoundPropagatesAsNotFoundError() {
        WireMockDownstreamConfig.stubProductNotFound(PRODUCT_ID);

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(
                        String.format(
                                """
                {"query":"query { product(id: \\"%s\\") { id } }"}
                """,
                                PRODUCT_ID))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.product", nullValue())
                .body("errors", hasSize(1))
                .body("errors[0].extensions.code", equalTo("NOT_FOUND"));
    }

    @Test
    void downstreamServerErrorPropagatesAsDownstreamError() {
        WireMockDownstreamConfig.stubProductServerError(PRODUCT_ID);

        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(
                        String.format(
                                """
                {"query":"query { product(id: \\"%s\\") { id } }"}
                """,
                                PRODUCT_ID))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.product", nullValue())
                .body("errors", hasSize(1))
                .body("errors[0].extensions.code", equalTo("DOWNSTREAM_SERVICE_ERROR"));
    }

    // =========================================================================
    // Cross-service query â€” one HTTP request, multiple downstream calls
    // =========================================================================

    @Test
    void crossServiceQueryAggregatesMultipleServices() {
        WireMockDownstreamConfig.stubOrder(
                ORDER_ID,
                "ORD-2025-0001001",
                "CONFIRMED",
                CUSTOMER_ID,
                "alice@omp.local",
                List.of(
                        new OrderLineStub(
                                PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", 1)));
        WireMockDownstreamConfig.stubCustomer(CUSTOMER_ID, "alice@omp.local", "Alice", "Customer");
        WireMockDownstreamConfig.stubProduct(
                PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", "USD");

        // One GraphQL query â†’ three downstream calls (order, customer, product).
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body(
                        String.format(
                                """
                {"query":"query { order(id: \\"%s\\") { orderNumber customer { firstName } lines { product { sku } } } }"}
                """,
                                ORDER_ID))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.order.orderNumber", equalTo("ORD-2025-0001001"))
                .body("data.order.customer.firstName", equalTo("Alice"))
                .body("data.order.lines[0].product.sku", equalTo("LAPTOP-PRO-14"))
                .body("errors", nullValue());
    }

    // =========================================================================
    // Malformed request handling
    // =========================================================================

    @Test
    void invalidGraphQLSyntaxReturnsError() {
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ this is not valid graphql }\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200) // GraphQL returns 200 with a parse error
                .body("errors", not(empty()));
    }

    @Test
    void missingQueryFieldReturns400() {
        // A request with no `query` field is malformed JSON for GraphQL.
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(400); // Spring GraphQL validates the request envelope
    }

    @Test
    void mutationForwardsJsonContentTypeAndAuthenticatedIdentity() {
        UUID created = UUID.randomUUID();
        WireMockDownstreamConfig.stubCreateProduct(
                created, "JSON-CONTRACT", "JSON product", "12.50", "USD");
        String query =
                "mutation { createProduct(input:{sku:\"JSON-CONTRACT\",name:\"JSON product\",categoryId:\"00000000-0000-0000-0000-000000000001\",price:{amount:\"12.50\",currency:\"USD\"}}) { id } }";
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType(ContentType.JSON)
                .body(Map.of("query", query))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.createProduct.id", equalTo(created.toString()))
                .body("errors", nullValue());
        productWireMock.verify(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(
                                        "/api/v1/products"))
                        .withHeader(
                                "Content-Type",
                                com.github.tomakehurst.wiremock.client.WireMock.containing(
                                        "application/json"))
                        .withHeader(
                                "Authorization",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                        "Bearer " + TestSecurityConfig.adminToken()))
                        .withRequestBody(
                                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                                        "$.sku",
                                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                                "JSON-CONTRACT"))));
    }
}

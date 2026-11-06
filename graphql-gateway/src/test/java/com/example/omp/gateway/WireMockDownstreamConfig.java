package com.example.omp.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Static helpers for stubbing the three downstream services.
 *
 * <p>Each helper method constructs a stub with the exact JSON shape the gateway's client DTOs
 * expect. Tests call these from their test methods (or from a {@code @BeforeEach} for common
 * setup).
 *
 * <p><b>Naming convention:</b>
 *
 * <ul>
 *   <li>{@code stubXxx(...)} — success responses (2xx).
 *   <li>{@code stubXxxNotFound(...)} — 404 responses.
 *   <li>{@code stubXxxServerError(...)} — 500 responses.
 *   <li>{@code stubXxxTimeout(...)} — delayed responses (to trigger timeouts).
 * </ul>
 *
 * <p><b>Reset between tests:</b> stubs accumulate on the WireMock server until cleared. Call {@link
 * AbstractGraphQlTest#resetWireMocks()} in each test's {@code @BeforeEach}.
 */
public final class WireMockDownstreamConfig {

    private WireMockDownstreamConfig() {
        // Utility class — no instances.
    }

    // =========================================================================
    // Product service
    // =========================================================================

    /** Stubs a successful product lookup. */
    public static void stubProduct(
            UUID id, String sku, String name, String amount, String currency) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "sku": "%s",
              "name": "%s",
              "description": "Test product",
              "category": {
                "id": "00000000-0000-0000-0000-000000000102",
                "name": "Laptops",
                "slug": "laptops"
              },
              "price": {"amount": %s, "currency": "%s"},
              "status": "ACTIVE",
              "images": [],
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, sku, name, amount, currency);

        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + id))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/batch"))
                        .withQueryParam("ids", equalTo(id.toString()))
                        .willReturn(okJson("[" + body + "]")));
    }

    /**
     * Stubs a successful product lookup with an arbitrary JSON body. Useful when the test needs to
     * control every field.
     */
    public static void stubProductWithBody(UUID id, String jsonBody) {
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + id))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(jsonBody)));
    }

    public static void stubProductNotFound(UUID id) {
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + id))
                        .willReturn(aResponse().withStatus(404)));
    }

    public static void stubProductServerError(UUID id) {
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + id))
                        .willReturn(aResponse().withStatus(500)));
    }

    /**
     * Stubs a delayed response. The delay exceeds Feign's test read timeout (2000ms), so the call
     * will fail with a timeout.
     */
    public static void stubProductTimeout(UUID id) {
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + id))
                        .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));
    }

    /** Stubs a product list response. */
    public static void stubProductList(String jsonBody) {
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(jsonBody)));
    }

    /** Stubs a successful product creation (POST). */
    public static void stubCreateProduct(
            UUID newId, String sku, String name, String amount, String currency) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "sku": "%s",
              "name": "%s",
              "description": null,
              "category": null,
              "price": {"amount": %s, "currency": "%s"},
              "status": "ACTIVE",
              "images": [],
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        newId, sku, name, amount, currency);

        AbstractGraphQlTest.productWireMock.stubFor(
                post(urlPathEqualTo("/api/v1/products"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /**
     * Stubs a category list (used by product queries that include category, or the categories()
     * root query).
     */
    public static void stubCategories(String jsonBody) {
        AbstractGraphQlTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/categories"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(jsonBody)));
    }

    // =========================================================================
    // Customer service
    // =========================================================================

    public static void stubCustomer(UUID id, String email, String firstName, String lastName) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "email": "%s",
              "keycloakSub": null,
              "firstName": "%s",
              "lastName": "%s",
              "phone": null,
              "dateOfBirth": null,
              "status": "ACTIVE",
              "addresses": [],
              "preferences": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, email, firstName, lastName);

        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + id))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/batch"))
                        .withQueryParam("ids", equalTo(id.toString()))
                        .willReturn(okJson("[" + body + "]")));
    }

    /**
     * Stubs a customer with a specific locale in preferences. Used by tests that query customer
     * preferences.
     */
    public static void stubCustomerWithPreferences(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String locale,
            String currency) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "email": "%s",
              "keycloakSub": null,
              "firstName": "%s",
              "lastName": "%s",
              "phone": null,
              "dateOfBirth": null,
              "status": "ACTIVE",
              "addresses": [],
              "preferences": {
                "locale": "%s",
                "currency": "%s",
                "marketingOptIn": false,
                "notificationEmail": true,
                "notificationSms": false
              },
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, email, firstName, lastName, locale, currency);

        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + id))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    public static void stubCustomerByEmail(
            UUID id, String email, String firstName, String lastName) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "email": "%s",
              "keycloakSub": null,
              "firstName": "%s",
              "lastName": "%s",
              "status": "ACTIVE",
              "addresses": [],
              "preferences": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, email, firstName, lastName);

        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo(
                                "/api/v1/customers/email/"
                                        + java.net.URLEncoder.encode(
                                                email, java.nio.charset.StandardCharsets.UTF_8)))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /** Stubs the {@code /me} endpoint. The gateway calls this for the {@code me} query resolver. */
    public static void stubCurrentCustomer(
            UUID id, String email, String firstName, String lastName) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "email": "%s",
              "keycloakSub": "test-alice",
              "firstName": "%s",
              "lastName": "%s",
              "status": "ACTIVE",
              "addresses": [],
              "preferences": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, email, firstName, lastName);

        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/me"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    public static void stubCustomerNotFound(UUID id) {
        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + id))
                        .willReturn(aResponse().withStatus(404)));
    }

    public static void stubCustomerServerError(UUID id) {
        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + id))
                        .willReturn(aResponse().withStatus(500)));
    }

    public static void stubCustomerList(String jsonBody) {
        AbstractGraphQlTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(jsonBody)));
    }

    /** Stubs a successful customer creation (POST). */
    public static void stubCreateCustomer(
            UUID newId, String email, String firstName, String lastName) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "email": "%s",
              "keycloakSub": null,
              "firstName": "%s",
              "lastName": "%s",
              "status": "ACTIVE",
              "addresses": [],
              "preferences": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        newId, email, firstName, lastName);

        AbstractGraphQlTest.customerWireMock.stubFor(
                post(urlPathEqualTo("/api/v1/customers"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /** Stubs a 409 conflict on create (duplicate email). */
    public static void stubCreateCustomerConflict(String message) {
        String body =
                String.format(
                        """
            {
              "type": "about:blank",
              "title": "Conflict",
              "status": 409,
              "detail": "%s"
            }
            """,
                        message);

        AbstractGraphQlTest.customerWireMock.stubFor(
                post(urlPathEqualTo("/api/v1/customers"))
                        .willReturn(
                                aResponse()
                                        .withStatus(409)
                                        .withHeader("Content-Type", "application/problem+json")
                                        .withBody(body)));
    }

    // =========================================================================
    // Order service
    // =========================================================================

    /**
     * Stubs an order lookup with a list of lines. Each line is a tuple {@code (productId, sku,
     * productName, unitPrice, quantity)}.
     */
    public static void stubOrder(
            UUID id,
            String orderNumber,
            String status,
            UUID customerId,
            String customerEmail,
            java.util.List<OrderLineStub> lines) {
        StringBuilder linesJson = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            OrderLineStub line = lines.get(i);
            if (i > 0) linesJson.append(",");
            linesJson.append(
                    String.format(
                            """
                {
                  "id": "00000000-0000-0000-0000-00000000%04d",
                  "productId": "%s",
                  "productSku": "%s",
                  "productName": "%s",
                  "unitPrice": {"amount": %s, "currency": "USD"},
                  "quantity": %d,
                  "lineTotal": %s
                }
                """,
                            i,
                            line.productId(),
                            line.sku(),
                            line.productName(),
                            line.unitPrice(),
                            line.quantity(),
                            new BigDecimal(line.unitPrice())
                                    .multiply(BigDecimal.valueOf(line.quantity()))));
        }
        linesJson.append("]");

        String body =
                String.format(
                        """
            {
              "id": "%s",
              "orderNumber": "%s",
              "customerId": "%s",
              "customerEmail": "%s",
              "status": "%s",
              "subtotal": {"amount": 0, "currency": "USD"},
              "tax": {"amount": 0, "currency": "USD"},
              "shipping": {"amount": 0, "currency": "USD"},
              "total": {"amount": 0, "currency": "USD"},
              "shippingAddress": {
                "line1": "1 Test Way", "city": "Testville",
                "state": "CA", "postalCode": "12345", "country": "US"
              },
              "lines": %s,
              "statusHistory": null,
              "placedAt": null,
              "notes": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, orderNumber, customerId, customerEmail, status, linesJson);

        AbstractGraphQlTest.orderWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/orders/" + id))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    public static void stubOrderNotFound(UUID id) {
        AbstractGraphQlTest.orderWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/orders/" + id))
                        .willReturn(aResponse().withStatus(404)));
    }

    public static void stubOrderServerError(UUID id) {
        AbstractGraphQlTest.orderWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/orders/" + id))
                        .willReturn(aResponse().withStatus(500)));
    }

    /** Stubs an order list response. */
    public static void stubOrderList(String jsonBody) {
        AbstractGraphQlTest.orderWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(jsonBody)));
    }

    /** Stubs a successful order creation (POST). */
    public static void stubCreateOrder(UUID newId, String orderNumber) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "orderNumber": "%s",
              "customerId": "00000000-0000-0000-0000-000000000101",
              "customerEmail": "alice@omp.local",
              "status": "DRAFT",
              "subtotal": {"amount": 0, "currency": "USD"},
              "tax": {"amount": 0, "currency": "USD"},
              "shipping": {"amount": 0, "currency": "USD"},
              "total": {"amount": 0, "currency": "USD"},
              "shippingAddress": {
                "line1": "1 Test Way", "city": "Testville",
                "state": "CA", "postalCode": "12345", "country": "US"
              },
              "lines": [],
              "statusHistory": null,
              "placedAt": null,
              "notes": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        newId, orderNumber);

        AbstractGraphQlTest.orderWireMock.stubFor(
                post(urlPathEqualTo("/api/v1/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /** Stubs a state transition (place / confirm / ship / deliver / cancel). */
    public static void stubOrderTransition(UUID id, String newStatus, String action) {
        stubOrderTransition(id, newStatus, action, 200);
    }

    public static void stubOrderTransition(
            UUID id, String newStatus, String action, int httpStatus) {
        String body =
                String.format(
                        """
            {
              "id": "%s",
              "orderNumber": "ORD-2025-0001234",
              "customerId": "00000000-0000-0000-0000-000000000101",
              "customerEmail": "alice@omp.local",
              "status": "%s",
              "subtotal": {"amount": 0, "currency": "USD"},
              "tax": {"amount": 0, "currency": "USD"},
              "shipping": {"amount": 0, "currency": "USD"},
              "total": {"amount": 0, "currency": "USD"},
              "shippingAddress": {
                "line1": "1 Test Way", "city": "Testville",
                "state": "CA", "postalCode": "12345", "country": "US"
              },
              "lines": [],
              "statusHistory": null,
              "placedAt": "2025-01-01T00:00:00Z",
              "notes": null,
              "createdAt": "2025-01-01T00:00:00Z",
              "updatedAt": "2025-01-01T00:00:00Z"
            }
            """,
                        id, newStatus);

        AbstractGraphQlTest.orderWireMock.stubFor(
                post(urlPathEqualTo("/api/v1/orders/" + id + "/" + action))
                        .willReturn(
                                aResponse()
                                        .withStatus(httpStatus)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /** Stubs a state transition that conflicts (illegal transition). */
    public static void stubOrderTransitionConflict(UUID id, String action, String message) {
        String body =
                String.format(
                        """
            {
              "type": "about:blank",
              "title": "Conflict",
              "status": 409,
              "detail": "%s"
            }
            """,
                        message);

        AbstractGraphQlTest.orderWireMock.stubFor(
                post(urlPathEqualTo("/api/v1/orders/" + id + "/" + action))
                        .willReturn(
                                aResponse()
                                        .withStatus(409)
                                        .withHeader("Content-Type", "application/problem+json")
                                        .withBody(body)));
    }

    // =========================================================================
    // Helper types
    // =========================================================================

    /** Convenience record for building order line stubs. */
    public record OrderLineStub(
            UUID productId, String sku, String productName, String unitPrice, int quantity) {}
}

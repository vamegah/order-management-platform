package com.example.omp.order;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helpers for stubbing downstream services in tests.
 *
 * <p>Usage in a test:
 *
 * <pre>
 *   stubProduct(someProductId(), "LAPTOP-PRO-14", "Pro Laptop 14\"", "2499.99", "USD");
 *   stubCustomer(someCustomerId(), "alice@omp.local", "Alice", "Customer");
 * </pre>
 *
 * <p>For failure scenarios, use the explicit failure helpers.
 */
public final class WireMockDownstreamConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockDownstreamConfig() {}

    // -------------------------------------------------------------------------
    // Product-service stubs
    // -------------------------------------------------------------------------

    public static void stubProduct(
            String productId, String sku, String name, String amount, String currency) {
        var response = JSON.createObjectNode();
        response.put("id", productId).put("sku", sku).put("name", name).put("status", "ACTIVE");
        response.putObject("price")
                .put("amount", new java.math.BigDecimal(amount))
                .put("currency", currency);

        AbstractIntegrationTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + productId))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(response.toString())));
    }

    public static void stubProductNotFound(String productId) {
        AbstractIntegrationTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + productId))
                        .willReturn(aResponse().withStatus(404)));
    }

    public static void stubProductServerError(String productId) {
        AbstractIntegrationTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + productId))
                        .willReturn(aResponse().withStatus(500)));
    }

    public static void stubProductTimeout(String productId) {
        AbstractIntegrationTest.productWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/products/" + productId))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(
                                                10_000))); // 10s delay > 2s test read timeout
    }

    // -------------------------------------------------------------------------
    // Customer-service stubs
    // -------------------------------------------------------------------------

    public static void stubCustomer(
            String customerId, String email, String firstName, String lastName) {
        var response = JSON.createObjectNode();
        response.put("id", customerId)
                .put("email", email)
                .put("firstName", firstName)
                .put("lastName", lastName)
                .put("status", "ACTIVE");
        response.putArray("addresses");
        response.putNull("preferences");

        AbstractIntegrationTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + customerId))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(response.toString())));
    }

    public static void stubCustomerNotFound(String customerId) {
        AbstractIntegrationTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + customerId))
                        .willReturn(aResponse().withStatus(404)));
    }

    public static void stubCustomerServerError(String customerId) {
        AbstractIntegrationTest.customerWireMock.stubFor(
                get(urlPathEqualTo("/api/v1/customers/" + customerId))
                        .willReturn(aResponse().withStatus(500)));
    }
}

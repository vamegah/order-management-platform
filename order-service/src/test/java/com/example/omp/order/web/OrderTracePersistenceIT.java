package com.example.omp.order.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.TestSecurityConfig;
import com.example.omp.order.WireMockDownstreamConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Real HTTP, observed Feign and RPC calls must preserve the trace in the committed event. */
@TestPropertySource(
        properties = {
            "management.tracing.enabled=true",
            "management.tracing.sampling.probability=1.0",
            "management.tracing.export.otlp.enabled=false"
        })
class OrderTracePersistenceIT extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void committedOrderEventRetainsTheInboundTraceAfterAllDownstreamCalls() {
        String customer = "00000000-0000-0000-0000-000000000101";
        String product = "00000000-0000-0000-0000-000000000201";
        String trace = "abcdef1234567890abcdef1234567890";
        WireMockDownstreamConfig.stubProduct(
                product, "LAPTOP-PRO-14", "Traced product", "10.25", "USD");
        WireMockDownstreamConfig.stubCustomer(customer, "alice@omp.local", "Alice", "Customer");
        String id =
                given().auth()
                        .oauth2(TestSecurityConfig.aliceToken())
                        .header("traceparent", "00-" + trace + "-abcdef1234567890-01")
                        .contentType("application/json")
                        .body(
                                Map.of(
                                        "customerId",
                                        customer,
                                        "shippingAddress",
                                        Map.of(
                                                "line1",
                                                "isolated",
                                                "city",
                                                "Test",
                                                "postalCode",
                                                "10001",
                                                "country",
                                                "US"),
                                        "lines",
                                        List.of(Map.of("productId", product, "quantity", 1))))
                        .post("/api/v1/orders")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");
        customerWireMock.verify(
                getRequestedFor(urlPathEqualTo("/api/v1/customers/" + customer))
                        .withHeader(
                                "traceparent",
                                matching("00-" + trace + "-[0-9a-f]{16}-[0-9a-f]{2}")));
        productWireMock.verify(
                getRequestedFor(urlPathEqualTo("/api/v1/products/" + product))
                        .withHeader(
                                "traceparent",
                                matching("00-" + trace + "-[0-9a-f]{16}-[0-9a-f]{2}")));
        String parent =
                jdbc.queryForObject(
                        "SELECT payload->>'traceparent' FROM orders.outbox_event WHERE aggregate_id=? AND event_type='ORDER_CREATED'",
                        String.class,
                        id);
        assertThat(parent).isNotNull().matches("00-" + trace + "-[0-9a-f]{16}-[0-9a-f]{2}");
    }
}

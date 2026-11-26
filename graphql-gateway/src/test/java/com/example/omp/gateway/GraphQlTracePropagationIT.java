package com.example.omp.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Real server, GraphQL worker and observed Feign transport must share the inbound W3C trace. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.tracing.enabled=true", "management.tracing.export.otlp.enabled=false",
            "management.tracing.sampling.probability=1.0", "spring.task.execution.pool.core-size=1",
            "spring.task.execution.pool.max-size=1"
        })
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class GraphQlTracePropagationIT extends AbstractGraphQlTest {
    @LocalServerPort int port;

    @Test
    void workerPropagatesInboundTraceAndDoesNotReuseItForTheNextCaller() {
        resetWireMocks();
        UUID product = UUID.fromString("00000000-0000-0000-0000-000000000201");
        WireMockDownstreamConfig.stubProduct(product, "TRACE-1", "Traced product", "10.01", "USD");
        String path = "/api/v1/products/" + product;
        String trace = "1234567890abcdef1234567890abcdef";
        query(product, "00-" + trace + "-1234567890abcdef-01");
        productWireMock.verify(
                getRequestedFor(urlPathEqualTo(path))
                        .withHeader("traceparent", matching("00-" + trace + "-[0-9a-f]{16}-01")));
        productWireMock.resetRequests();
        query(product, null);
        var requests = productWireMock.findAll(getRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().getHeader("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                .doesNotContain(trace);
        assertThat(
                        Integer.parseInt(
                                        requests.getFirst().getHeader("traceparent").substring(53),
                                        16)
                                & 1)
                .isEqualTo(1);
    }

    @Test
    void mutationCarriesTheCallerTraceToTheOrderWrite() {
        resetWireMocks();
        UUID order = UUID.randomUUID();
        WireMockDownstreamConfig.stubCreateOrder(order, "TRACE-ORDER");
        String trace = "abcdef1234567890abcdef1234567890";
        given().baseUri("http://localhost")
                .port(port)
                .auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType(ContentType.JSON)
                .header("traceparent", "00-" + trace + "-abcdef1234567890-01")
                .body(
                        java.util.Map.of(
                                "query",
                                "mutation { createOrder(input: {customerId: \"00000000-0000-0000-0000-000000000101\", shippingAddress: {line1: \"isolated\", city: \"Test\", postalCode: \"10001\", country: \"US\"}, lines: [{productId: \"00000000-0000-0000-0000-000000000201\", quantity: 1}]}) { id } }"))
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", org.hamcrest.Matchers.nullValue())
                .body("data.createOrder.id", org.hamcrest.Matchers.equalTo(order.toString()));
        orderWireMock.verify(
                postRequestedFor(urlPathEqualTo("/api/v1/orders"))
                        .withHeader("traceparent", matching("00-" + trace + "-[0-9a-f]{16}-01")));
    }

    private void query(UUID product, String traceparent) {
        var request =
                given().baseUri("http://localhost")
                        .port(port)
                        .auth()
                        .oauth2(TestSecurityConfig.aliceToken())
                        .contentType(ContentType.JSON);
        if (traceparent != null) request.header("traceparent", traceparent);
        request.body(java.util.Map.of("query", "{ product(id: \"" + product + "\") { id name } }"))
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("errors", org.hamcrest.Matchers.nullValue())
                .body("data.product.id", org.hamcrest.Matchers.equalTo(product.toString()));
    }
}

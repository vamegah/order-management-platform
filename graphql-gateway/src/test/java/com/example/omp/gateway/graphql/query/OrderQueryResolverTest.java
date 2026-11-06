package com.example.omp.gateway.graphql.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.gateway.AbstractGraphQlTest;
import com.example.omp.gateway.TestSecurityConfig;
import com.example.omp.gateway.WireMockDownstreamConfig;
import com.example.omp.gateway.WireMockDownstreamConfig.OrderLineStub;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ActiveProfiles;

/** Unit tests for {@link OrderQueryResolver}. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class OrderQueryResolverTest extends AbstractGraphQlTest {

    @Autowired private GraphQlTester graphQlTester;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String ORDER_NUMBER = "ORD-2025-0001001";

    @BeforeEach
    void reset() {
        resetWireMocks();
    }

    @Test
    void orderReturnsStubbedOrder() {
        WireMockDownstreamConfig.stubOrder(
                ORDER_ID,
                ORDER_NUMBER,
                "DELIVERED",
                CUSTOMER_ID,
                "alice@omp.local",
                List.of(
                        new OrderLineStub(
                                PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", 1)));

        graphQlTester
                .document(
                        """
                query Order($id: ID!) {
                  order(id: $id) {
                    id
                    orderNumber
                    status
                    lines { productSku quantity }
                  }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("order.id")
                .entity(String.class)
                .isEqualTo(ORDER_ID.toString())
                .path("order.orderNumber")
                .entity(String.class)
                .isEqualTo(ORDER_NUMBER)
                .path("order.status")
                .entity(String.class)
                .isEqualTo("DELIVERED")
                .path("order.lines")
                .entityList(java.util.Map.class)
                .hasSize(1)
                .path("order.lines[0].productSku")
                .entity(String.class)
                .isEqualTo("LAPTOP-PRO-14")
                .path("order.lines[0].quantity")
                .entity(Integer.class)
                .isEqualTo(1);
    }

    @Test
    void orderReturnsErrorWhenNotFound() {
        WireMockDownstreamConfig.stubOrderNotFound(ORDER_ID);

        graphQlTester
                .document(
                        """
                query Order($id: ID!) {
                  order(id: $id) { id }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .errors()
                .satisfy(
                        errors -> {
                            assertThat(errors).hasSize(1);
                            assertThat(errors.get(0).getExtensions())
                                    .containsEntry("code", "NOT_FOUND");
                        })
                .path("order")
                .valueIsNull();
    }

    @Test
    void orderByNumberReturnsMatching() {
        WireMockDownstreamConfig.stubOrder(
                ORDER_ID, ORDER_NUMBER, "CONFIRMED", CUSTOMER_ID, "alice@omp.local", List.of());
        AbstractGraphQlTest.orderWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/orders/number/" + ORDER_NUMBER))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "id": "%s", "orderNumber": "%s", "status": "CONFIRMED",
                          "customerId": "%s", "customerEmail": "alice@omp.local",
                          "subtotal": {"amount": 0, "currency": "USD"},
                          "tax": {"amount": 0, "currency": "USD"},
                          "shipping": {"amount": 0, "currency": "USD"},
                          "total": {"amount": 0, "currency": "USD"},
                          "shippingAddress": {"line1": "1", "city": "X",
                            "postalCode": "1", "country": "US"},
                          "lines": [], "statusHistory": null,
                          "placedAt": null, "notes": null,
                          "createdAt": "2025-01-01T00:00:00Z",
                          "updatedAt": "2025-01-01T00:00:00Z"
                        }
                        """
                                                        .formatted(
                                                                ORDER_ID,
                                                                ORDER_NUMBER,
                                                                CUSTOMER_ID))));

        graphQlTester
                .document(
                        """
                query OrderByNumber($n: String!) {
                  orderByNumber(orderNumber: $n) { orderNumber status }
                }
                """)
                .variable("n", ORDER_NUMBER)
                .execute()
                .path("orderByNumber.orderNumber")
                .entity(String.class)
                .isEqualTo(ORDER_NUMBER)
                .path("orderByNumber.status")
                .entity(String.class)
                .isEqualTo("CONFIRMED");
    }

    @Test
    void ordersReturnsPaginatedList() {
        WireMockDownstreamConfig.stubOrderList(
                """
            {
              "content": [
                {
                  "id": "00000000-0000-0000-0000-000000000301",
                  "orderNumber": "ORD-2025-0001001", "status": "DELIVERED",
                  "customerId": "00000000-0000-0000-0000-000000000101",
                  "customerEmail": "alice@omp.local",
                  "subtotal": {"amount": 0, "currency": "USD"},
                  "tax": {"amount": 0, "currency": "USD"},
                  "shipping": {"amount": 0, "currency": "USD"},
                  "total": {"amount": 0, "currency": "USD"},
                  "shippingAddress": {"line1": "1", "city": "X",
                    "postalCode": "1", "country": "US"},
                  "lines": [], "statusHistory": null,
                  "placedAt": null, "notes": null,
                  "createdAt": "2025-01-01T00:00:00Z",
                  "updatedAt": "2025-01-01T00:00:00Z"
                }
              ],
              "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
              "first": true, "last": true
            }
            """);

        graphQlTester
                .document("{ orders { content { orderNumber } totalElements } }")
                .execute()
                .path("orders.content")
                .entityList(java.util.Map.class)
                .hasSize(1)
                .path("orders.totalElements")
                .entity(Integer.class)
                .isEqualTo(1);
    }
}

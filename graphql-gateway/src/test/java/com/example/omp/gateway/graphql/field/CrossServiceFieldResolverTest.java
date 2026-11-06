package com.example.omp.gateway.graphql.field;

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

/**
 * Unit tests for {@link CrossServiceFieldResolver}.
 *
 * <p>These fields resolve by calling a different service than the parent type:
 *
 * <ul>
 *   <li>{@code Customer.orders} → order-service
 *   <li>{@code Order.customer} → customer-service
 *   <li>{@code OrderLine.product} → product-service
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class CrossServiceFieldResolverTest extends AbstractGraphQlTest {

    @Test
    void multipleUniqueProductsUseOneBatchRequest() {
        UUID other = UUID.randomUUID();
        WireMockDownstreamConfig.stubOrder(
                ORDER_ID,
                "ORD-BATCH",
                "CONFIRMED",
                CUSTOMER_ID,
                "alice@omp.local",
                List.of(
                        new OrderLineStub(PRODUCT_ID, "SKU-1", "First", "1", 1),
                        new OrderLineStub(other, "SKU-2", "Second", "2", 1),
                        new OrderLineStub(PRODUCT_ID, "SKU-1", "First", "1", 1)));
        productWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/batch"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.okJson(
                                        "[{\"id\":\""
                                                + other
                                                + "\",\"name\":\"Second\"},{\"id\":\""
                                                + PRODUCT_ID
                                                + "\",\"name\":\"First\"}]")));
        graphQlTester
                .document("query($id:ID!){order(id:$id){lines{product{name}}}}")
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("order.lines[*].product.name")
                .entityList(String.class)
                .containsExactly("First", "Second", "First");
        productWireMock.verify(
                1,
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/batch"))
                        .withQueryParam(
                                "ids",
                                com.github.tomakehurst.wiremock.client.WireMock.containing(
                                        PRODUCT_ID.toString())));
        org.assertj.core.api.Assertions.assertThat(productWireMock.getAllServeEvents()).hasSize(1);
    }

    @Autowired private GraphQlTester graphQlTester;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @BeforeEach
    void reset() {
        resetWireMocks();
    }

    // =========================================================================
    // Order.customer — the customer-service call
    // =========================================================================

    @Test
    void orderCustomerResolvesFromCustomerService() {
        // Stub the order (which will be the source object)
        WireMockDownstreamConfig.stubOrder(
                ORDER_ID, "ORD-2025-0001001", "DRAFT", CUSTOMER_ID, "alice@omp.local", List.of());
        // Stub the customer (which the cross-service field will fetch)
        WireMockDownstreamConfig.stubCustomer(CUSTOMER_ID, "alice@omp.local", "Alice", "Customer");

        // Query order(id) then navigate to the cross-service `customer` field
        graphQlTester
                .document(
                        """
                query Order($id: ID!) {
                  order(id: $id) {
                    id
                    customer { email firstName }
                  }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("order.customer.email")
                .entity(String.class)
                .isEqualTo("alice@omp.local")
                .path("order.customer.firstName")
                .entity(String.class)
                .isEqualTo("Alice");
    }

    // =========================================================================
    // OrderLine.product — the product-service call
    // =========================================================================

    @Test
    void orderLineProductResolvesFromProductService() {
        WireMockDownstreamConfig.stubOrder(
                ORDER_ID,
                "ORD-2025-0001001",
                "CONFIRMED",
                CUSTOMER_ID,
                "alice@omp.local",
                List.of(
                        new OrderLineStub(
                                PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", 1)));
        WireMockDownstreamConfig.stubProduct(
                PRODUCT_ID, "LAPTOP-PRO-14", "Pro Laptop", "2499.99", "USD");

        graphQlTester
                .document(
                        """
                query Order($id: ID!) {
                  order(id: $id) {
                    id
                    lines {
                      productSku
                      product { name price { amount currency } }
                    }
                  }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("order.lines[0].product.name")
                .entity(String.class)
                .isEqualTo("Pro Laptop")
                .path("order.lines[0].product.price.amount")
                .entity(String.class)
                .isEqualTo("2499.99")
                .path("order.lines[0].product.price.currency")
                .entity(String.class)
                .isEqualTo("USD");
    }

    // =========================================================================
    // Customer.orders — the order-service call
    // =========================================================================

    @Test
    void customerOrdersResolvesFromOrderService() {
        WireMockDownstreamConfig.stubCustomer(CUSTOMER_ID, "alice@omp.local", "Alice", "Customer");
        WireMockDownstreamConfig.stubOrderList(
                """
            {
              "content": [
                {
                  "id": "%s", "orderNumber": "ORD-2025-0001001", "status": "DELIVERED",
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
              ],
              "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
              "first": true, "last": true
            }
            """
                        .formatted(ORDER_ID, CUSTOMER_ID));

        graphQlTester
                .document(
                        """
                query Customer($id: ID!) {
                  customer(id: $id) {
                    email
                    orders { orderNumber status }
                  }
                }
                """)
                .variable("id", CUSTOMER_ID.toString())
                .execute()
                .path("customer.orders")
                .entityList(java.util.Map.class)
                .hasSize(1)
                .path("customer.orders[0].orderNumber")
                .entity(String.class)
                .isEqualTo("ORD-2025-0001001");
    }

    // =========================================================================
    // Deep query — all three cross-service fields in one query
    // =========================================================================

    @Test
    void fullCrossServiceQueryResolves() {
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

        graphQlTester
                .document(
                        """
                query Order($id: ID!) {
                  order(id: $id) {
                    orderNumber
                    customer { firstName }
                    lines {
                      product { name }
                    }
                  }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("order.orderNumber")
                .entity(String.class)
                .isEqualTo("ORD-2025-0001001")
                .path("order.customer.firstName")
                .entity(String.class)
                .isEqualTo("Alice")
                .path("order.lines[0].product.name")
                .entity(String.class)
                .isEqualTo("Pro Laptop");
    }
}

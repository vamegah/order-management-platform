package com.example.omp.gateway.graphql.mutation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.gateway.AbstractGraphQlTest;
import com.example.omp.gateway.TestSecurityConfig;
import com.example.omp.gateway.WireMockDownstreamConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

/** Unit tests for {@link OrderMutationResolver}. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class OrderMutationResolverTest extends AbstractGraphQlTest {

    @Autowired private GraphQlTester graphQlTester;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @BeforeEach
    void reset() {
        resetWireMocks();
    }

    // =========================================================================
    // createOrder — success
    // =========================================================================

    @Test
    @WithMockUser(roles = "customer")
    void createOrderReturnsDraftOrder() {
        WireMockDownstreamConfig.stubCreateOrder(ORDER_ID, "ORD-2025-0001234");

        graphQlTester
                .document(
                        """
                mutation CreateOrder($input: CreateOrderInput!) {
                  createOrder(input: $input) {
                    id
                    orderNumber
                    status
                  }
                }
                """)
                .variable(
                        "input",
                        java.util.Map.of(
                                "customerId", CUSTOMER_ID.toString(),
                                "shippingAddress",
                                        java.util.Map.of(
                                                "line1", "1 Test Way",
                                                "city", "Testville",
                                                "state", "CA",
                                                "postalCode", "12345",
                                                "country", "US"),
                                "lines",
                                        List.of(
                                                java.util.Map.of(
                                                        "productId",
                                                        PRODUCT_ID.toString(),
                                                        "quantity",
                                                        2))))
                .execute()
                .path("createOrder.id")
                .entity(String.class)
                .isEqualTo(ORDER_ID.toString())
                .path("createOrder.orderNumber")
                .entity(String.class)
                .isEqualTo("ORD-2025-0001234")
                .path("createOrder.status")
                .entity(String.class)
                .isEqualTo("DRAFT");
    }

    // =========================================================================
    // placeOrder — DRAFT → PLACED
    // =========================================================================

    @Test
    @WithMockUser(roles = "customer")
    void placeOrderTransitionsToPlaced() {
        WireMockDownstreamConfig.stubOrderTransition(ORDER_ID, "PLACED", "place");

        graphQlTester
                .document(
                        """
                mutation PlaceOrder($id: ID!) {
                  placeOrder(id: $id) { status }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("placeOrder.status")
                .entity(String.class)
                .isEqualTo("PLACED");
    }

    // =========================================================================
    // confirmOrder — admin-only
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void confirmOrderAsAdminTransitionsToConfirmed() {
        WireMockDownstreamConfig.stubOrderTransition(ORDER_ID, "CONFIRMED", "confirm");

        graphQlTester
                .document(
                        """
                mutation ConfirmOrder($id: ID!, $reason: String) {
                  confirmOrder(id: $id, reason: $reason) { status }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .variable("reason", "payment captured")
                .execute()
                .path("confirmOrder.status")
                .entity(String.class)
                .isEqualTo("CONFIRMED");
    }

    @Test
    @WithMockUser(roles = "customer")
    void confirmOrderAsNonAdminReturnsForbidden() {
        graphQlTester
                .document(
                        """
                mutation ConfirmOrder($id: ID!) {
                  confirmOrder(id: $id) { status }
                }
                """)
                .variable("id", ORDER_ID.toString())
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
    // shipOrder — admin-only
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void shipOrderTransitionsToShipped() {
        WireMockDownstreamConfig.stubOrderTransition(ORDER_ID, "SHIPPED", "ship");

        graphQlTester
                .document(
                        """
                mutation ShipOrder($id: ID!) {
                  shipOrder(id: $id, reason: "shipped via UPS") { status }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("shipOrder.status")
                .entity(String.class)
                .isEqualTo("SHIPPED");
    }

    // =========================================================================
    // cancelOrder — allowed for owner and admin
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void shipOrderAcceptsPendingFulfillmentFromHttp202() {
        WireMockDownstreamConfig.stubOrderTransition(ORDER_ID, "FULFILLING", "ship", 202);
        graphQlTester
                .document("mutation($id: ID!) { shipOrder(id: $id) { status } }")
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("shipOrder.status")
                .entity(String.class)
                .isEqualTo("FULFILLING");
    }

    @Test
    @WithMockUser(roles = "customer")
    void cancelOrderAsCustomerTransitionsToCancelled() {
        WireMockDownstreamConfig.stubOrderTransition(ORDER_ID, "CANCELLED", "cancel");

        graphQlTester
                .document(
                        """
                mutation CancelOrder($id: ID!) {
                  cancelOrder(id: $id, reason: "changed mind") { status }
                }
                """)
                .variable("id", ORDER_ID.toString())
                .execute()
                .path("cancelOrder.status")
                .entity(String.class)
                .isEqualTo("CANCELLED");
    }

    // =========================================================================
    // Illegal transition — 409 conflict
    // =========================================================================

    @Test
    @WithMockUser(roles = "admin")
    void shipOrderWithIllegalTransitionReturnsConflict() {
        WireMockDownstreamConfig.stubOrderTransitionConflict(
                ORDER_ID, "ship", "Cannot ship an order in status DELIVERED");

        graphQlTester
                .document(
                        """
                mutation ShipOrder($id: ID!) {
                  shipOrder(id: $id) { status }
                }
                """)
                .variable("id", ORDER_ID.toString())
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
}

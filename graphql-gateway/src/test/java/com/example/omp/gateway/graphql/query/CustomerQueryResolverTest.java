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

/** Unit tests for {@link CustomerQueryResolver}. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class CustomerQueryResolverTest extends AbstractGraphQlTest {

    @Autowired private GraphQlTester graphQlTester;

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String EMAIL = "alice@omp.local";

    @BeforeEach
    void reset() {
        resetWireMocks();
    }

    @Test
    void customerReturnsStubbedCustomer() {
        WireMockDownstreamConfig.stubCustomer(CUSTOMER_ID, EMAIL, "Alice", "Customer");

        graphQlTester
                .document(
                        """
                query Customer($id: ID!) {
                  customer(id: $id) {
                    id
                    email
                    firstName
                    lastName
                    status
                  }
                }
                """)
                .variable("id", CUSTOMER_ID.toString())
                .execute()
                .path("customer.id")
                .entity(String.class)
                .isEqualTo(CUSTOMER_ID.toString())
                .path("customer.email")
                .entity(String.class)
                .isEqualTo(EMAIL)
                .path("customer.firstName")
                .entity(String.class)
                .isEqualTo("Alice")
                .path("customer.lastName")
                .entity(String.class)
                .isEqualTo("Customer")
                .path("customer.status")
                .entity(String.class)
                .isEqualTo("ACTIVE");
    }

    @Test
    void customerReturnsErrorWhenNotFound() {
        WireMockDownstreamConfig.stubCustomerNotFound(CUSTOMER_ID);

        graphQlTester
                .document(
                        """
                query Customer($id: ID!) {
                  customer(id: $id) { id }
                }
                """)
                .variable("id", CUSTOMER_ID.toString())
                .execute()
                .errors()
                .satisfy(
                        errors -> {
                            assertThat(errors).hasSize(1);
                            assertThat(errors.get(0).getExtensions())
                                    .containsEntry("code", "NOT_FOUND");
                        })
                .path("customer")
                .valueIsNull();
    }

    @Test
    void customerByEmailReturnsMatching() {
        WireMockDownstreamConfig.stubCustomerByEmail(CUSTOMER_ID, EMAIL, "Alice", "Customer");

        graphQlTester
                .document(
                        """
                query CustomerByEmail($email: String!) {
                  customerByEmail(email: $email) { email }
                }
                """)
                .variable("email", EMAIL)
                .execute()
                .path("customerByEmail.email")
                .entity(String.class)
                .isEqualTo(EMAIL);
    }

    @Test
    void meReturnsCurrentUser() {
        WireMockDownstreamConfig.stubCurrentCustomer(CUSTOMER_ID, EMAIL, "Alice", "Customer");

        graphQlTester
                .document("{ me { email firstName } }")
                .execute()
                .path("me.email")
                .entity(String.class)
                .isEqualTo(EMAIL)
                .path("me.firstName")
                .entity(String.class)
                .isEqualTo("Alice");
    }

    @Test
    void customersReturnsPaginatedList() {
        WireMockDownstreamConfig.stubCustomerList(
                """
            {
              "content": [
                {
                  "id": "00000000-0000-0000-0000-000000000101",
                  "email": "alice@omp.local", "firstName": "Alice", "lastName": "Customer",
                  "status": "ACTIVE", "addresses": [], "preferences": null,
                  "createdAt": "2025-01-01T00:00:00Z", "updatedAt": "2025-01-01T00:00:00Z"
                }
              ],
              "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
              "first": true, "last": true
            }
            """);

        graphQlTester
                .document("{ customers { content { email } totalElements } }")
                .execute()
                .path("customers.content")
                .entityList(java.util.Map.class)
                .hasSize(1)
                .path("customers.totalElements")
                .entity(Integer.class)
                .isEqualTo(1);
    }
}

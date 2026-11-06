package com.example.omp.order;

import com.example.omp.order.clients.grpc.FakeInventoryService;
import com.example.omp.order.clients.grpc.FakePaymentService;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.grpc.Server;
import io.grpc.inprocess.InProcessServerBuilder;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real HTTP/database tests with Spring-owned PostgreSQL and in-process downstream RPC servers. Each
 * class receives a fresh context and downstream state. HTTP stub ports remain JVM-scoped; their
 * stubs are reset before every test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, AbstractIntegrationTest.Dependencies.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("omp")
                    .withUsername("omp_admin")
                    .withPassword("test")
                    .withInitScript("test-init.sql")
                    .withUrlParam("currentSchema", "orders,public");
        }

        @Bean(destroyMethod = "shutdownNow")
        Server inventoryTestServer() throws java.io.IOException {
            return InProcessServerBuilder.forName("inventory-service")
                    .directExecutor()
                    .addService(new FakeInventoryService())
                    .build()
                    .start();
        }

        @Bean(destroyMethod = "shutdownNow")
        Server paymentTestServer() throws java.io.IOException {
            return InProcessServerBuilder.forName("payment-service")
                    .directExecutor()
                    .addService(new FakePaymentService())
                    .build()
                    .start();
        }
    }

    protected static final WireMockServer productWireMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    protected static final WireMockServer customerWireMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        productWireMock.start();
        customerWireMock.start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    productWireMock.stop();
                                    customerWireMock.stop();
                                }));
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "omp.clients.product-service.url",
                () -> "http://localhost:" + productWireMock.port());
        registry.add(
                "omp.clients.customer-service.url",
                () -> "http://localhost:" + customerWireMock.port());
        registry.add("grpc.client.inventory-service.address", () -> "in-process:inventory-service");
        registry.add("grpc.client.payment-service.address", () -> "in-process:payment-service");
        registry.add("grpc.client.inventory-service.negotiation-type", () -> "PLAINTEXT");
        registry.add("grpc.client.payment-service.negotiation-type", () -> "PLAINTEXT");
    }

    @LocalServerPort protected int port;
    @Autowired protected PaymentClient payments;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        resetWireMocks();
    }

    protected static void resetWireMocks() {
        productWireMock.resetAll();
        customerWireMock.resetAll();
    }

    protected String createDraft() {
        String customer = "00000000-0000-0000-0000-000000000101";
        String product = "00000000-0000-0000-0000-000000000201";
        WireMockDownstreamConfig.stubProduct(
                product, "LAPTOP-PRO-14", "Pro Laptop 14\"", "10.25", "USD");
        WireMockDownstreamConfig.stubCustomer(customer, "alice@omp.local", "Alice", "Customer");
        var body =
                java.util.Map.of(
                        "customerId", customer,
                        "shippingAddress",
                                java.util.Map.of(
                                        "line1",
                                        "200 Shipping Ave",
                                        "city",
                                        "Oakland",
                                        "postalCode",
                                        "94607",
                                        "country",
                                        "US"),
                        "lines",
                                java.util.List.of(
                                        java.util.Map.of("productId", product, "quantity", 1)));
        return io.restassured.RestAssured.given()
                .auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .body(body)
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}

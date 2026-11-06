package com.example.omp.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for GraphQL gateway tests.
 *
 * <p>Provides:
 *
 * <ul>
 *   <li>Three static WireMock servers — one per downstream service.
 *   <li>{@code @DynamicPropertySource} wiring that points the Feign clients at WireMock (instead of
 *       the real services).
 *   <li>A {@link #resetWireMocks()} helper that tests call in {@code @BeforeEach}.
 * </ul>
 *
 * <p><b>Why no Spring Boot test annotation on this class?</b> Because tests use different slices:
 *
 * <ul>
 *   <li>{@code @SpringBootTest} — a fast slice for resolver unit tests. Loads only the GraphQL
 *       infrastructure and the specified resolver.
 *   <li>{@code @SpringBootTest(webEnvironment = RANDOM_PORT)} — a full context for integration
 *       tests against the real HTTP endpoint.
 * </ul>
 *
 * Putting a Spring Boot annotation on the base class would force all subclasses into the same
 * slice, defeating the purpose.
 *
 * <p><b>WireMock lifecycle:</b> servers are static — started once per JVM, shared across all test
 * classes. Tests call {@link #resetWireMocks()} in {@code @BeforeEach} to clear stubs and recorded
 * requests, ensuring isolation.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestSecurityConfig.class)
 * class ProductQueryResolverTest extends AbstractGraphQlTest {
 *
 *     @Autowired GraphQlTester graphQlTester;
 *
 *     @BeforeEach
 *     void reset() { resetWireMocks(); }
 *
 *     @Test
 *     void queryReturnsProduct() {
 *         stubProduct(id, "SKU-1", "Widget", "9.99", "USD");
 *         // ...
 *     }
 * }
 * }</pre>
 */
@org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
@org.springframework.security.test.context.support.WithMockUser(roles = "admin")
public abstract class AbstractGraphQlTest {

    // -------------------------------------------------------------------------
    // WireMock servers — one per downstream service.
    // -------------------------------------------------------------------------
    // Using three separate servers (instead of one with path-based routing)
    // lets tests verify calls against a specific service. E.g.:
    //
    //   productWireMock.verify(exactly(1),
    //       getRequestedFor(urlEqualTo("/api/v1/products/" + id)));
    //
    // is clearer than counting calls on a shared server and filtering by path.
    // -------------------------------------------------------------------------

    /** WireMock server for product-service stubs. */
    protected static final WireMockServer productWireMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    /** WireMock server for customer-service stubs. */
    protected static final WireMockServer customerWireMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    /** WireMock server for order-service stubs. */
    protected static final WireMockServer orderWireMock =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        productWireMock.start();
        customerWireMock.start();
        orderWireMock.start();

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    productWireMock.stop();
                                    customerWireMock.stop();
                                    orderWireMock.stop();
                                }));
    }

    // -------------------------------------------------------------------------
    // Spring property wiring — runs before context initialization.
    // -------------------------------------------------------------------------
    // Points the Feign clients at the WireMock servers, replacing the
    // placeholder URLs from application-test.yml.
    // -------------------------------------------------------------------------

    @DynamicPropertySource
    static void registerWireMockUrls(DynamicPropertyRegistry registry) {
        registry.add(
                "omp.clients.product-service.url",
                () -> "http://localhost:" + productWireMock.port());
        registry.add(
                "omp.clients.customer-service.url",
                () -> "http://localhost:" + customerWireMock.port());
        registry.add(
                "omp.clients.order-service.url", () -> "http://localhost:" + orderWireMock.port());
    }

    // -------------------------------------------------------------------------
    // Lifecycle helpers
    // -------------------------------------------------------------------------

    /**
     * Clears stubs and recorded requests on all three WireMock servers.
     *
     * <p>Call from a {@code @BeforeEach} method in each test class:
     *
     * <pre>{@code
     * @BeforeEach
     * void reset() { resetWireMocks(); }
     * }</pre>
     *
     * <p>Alternatively, extend this class and annotate the reset method here — but we leave it
     * explicit in subclasses so the behavior is visible in each test class.
     */
    protected static void resetWireMocks() {
        productWireMock.resetAll();
        customerWireMock.resetAll();
        orderWireMock.resetAll();
    }
}

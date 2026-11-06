package com.example.omp.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
        properties = {
            "omp.graphql.protection.requests-per-window=2",
            "omp.graphql.protection.max-request-bytes=512",
            "omp.graphql.protection.max-depth=3",
            "omp.graphql.protection.max-complexity=100",
            "spring.cloud.openfeign.client.config.default.readTimeout=15000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class GraphQlHttpProtectionTest extends AbstractGraphQlTest {

    @Test
    void persistedQueriesAreRejectedByTheHttpEndpoint() throws Exception {
        execute(
                        mapper.writeValueAsString(
                                Map.of(
                                        "query",
                                        "{__typename}",
                                        "extensions",
                                        Map.of(
                                                "persistedQuery",
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "sha256Hash",
                                                        "0".repeat(64))))))
                .andExpect(
                        jsonPath("$.errors[0].extensions.code")
                                .value("PERSISTED_QUERIES_UNSUPPORTED"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void slowDownstreamHttpCallReturnsTimeoutBeforeTheProviderResponds() throws Exception {
        UUID id = UUID.randomUUID();
        productWireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/" + id))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock.okJson("{}")
                                        .withFixedDelay(10000)));
        long started = System.nanoTime();
        query("{product(id:\"" + id + "\"){name}}")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("QUERY_TIMEOUT"));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(java.time.Duration.ofSeconds(9));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private String identity;

    @BeforeEach
    void reset() {
        resetWireMocks();
        identity = UUID.randomUUID().toString();
    }

    private ResultActions execute(String body) throws Exception {
        var action =
                mvc.perform(
                        post("/graphql")
                                .servletPath("/graphql")
                                .with(
                                        jwt().jwt(builder -> builder.subject(identity))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_admin")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body));
        var result = action.andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : action;
    }

    private ResultActions query(String document) throws Exception {
        return execute(mapper.writeValueAsString(Map.of("query", document)));
    }

    @Test
    void normalHttpQueriesExecuteAndThirdRequestIsLimitedBeforeDownstreamCall() throws Exception {
        UUID id = UUID.randomUUID();
        WireMockDownstreamConfig.stubProduct(id, "SKU", "Product", "1", "USD");
        String document = "{product(id:\"" + id + "\"){name}}";
        query(document)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product.name").value("Product"))
                .andExpect(header().string("Cache-Control", "no-store"));
        query(document).andExpect(status().isOk());
        query(document)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RATE_LIMITED"));
        assertThat(productWireMock.getAllServeEvents()).hasSize(2);
        productWireMock.verify(
                2,
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/api/v1/products/" + id))
                        .withHeader(
                                "Authorization",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                        "Bearer token")));
    }

    @Test
    void deepAndExpensiveHttpQueriesAreRejectedBeforeAnyDownstreamCalls() throws Exception {
        query("{order(id:\"" + UUID.randomUUID() + "\"){customer{orders{orderNumber}}}}")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("QUERY_DEPTH_LIMIT"));
        query("{products(size:100){content{id name}}}")
                .andExpect(jsonPath("$.errors[0].extensions.code").value("QUERY_COMPLEXITY_LIMIT"));
        assertThat(orderWireMock.getAllServeEvents()).isEmpty();
        assertThat(productWireMock.getAllServeEvents()).isEmpty();
        assertThat(customerWireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void oversizedHttpRequestNeverReachesGraphQlExecution() throws Exception {
        execute(
                        mapper.writeValueAsString(
                                Map.of(
                                        "query",
                                        "{__typename}",
                                        "variables",
                                        Map.of("large", "x".repeat(600)))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("REQUEST_TOO_LARGE"));
    }
}

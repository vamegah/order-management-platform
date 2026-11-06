package com.example.omp.gateway.config;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class GraphQlRequestFilterTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private GraphQlRequestFilter filter(int requests, int identities) {
        return new GraphQlRequestFilter(
                new GraphQlProtectionProperties(
                        12,
                        2000,
                        16,
                        requests,
                        identities,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1)),
                new ObjectMapper(),
                "/graphql");
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest("POST", "/graphql");
        request.setServletPath("/graphql");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void fixedLengthAndChunkedOversizedRequestsNeverReachExecution() throws Exception {
        var filter = filter(10, 10);
        var calls = new AtomicInteger();
        var response = new MockHttpServletResponse();
        filter.doFilter(request("x".repeat(17)), response, (req, res) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
        var chunked =
                new MockHttpServletRequest("POST", "/graphql") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        chunked.setServletPath("/graphql");
        chunked.setContent("x".repeat(17).getBytes());
        response = new MockHttpServletResponse();
        filter.doFilter(chunked, response, (req, res) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rateLimitsAreIsolatedByIdentityAndMemoryCapacityCannotBeEvaded() throws Exception {
        var filter = filter(1, 2);
        String[] users = {"one", "one", "two", "three", "four"};
        int[] statuses = {200, 429, 200, 429, 429};
        for (int i = 0; i < users.length; i++) {
            String user = users[i];
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    user, "unused", java.util.List.of()));
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request("{}"), response, (req, res) -> res.getWriter().write("accepted"));
            assertThat(response.getStatus()).isEqualTo(statuses[i]);
            if (statuses[i] == 429) {
                assertThat(response.getContentAsString()).contains("RATE_LIMITED");
            }
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void boundedBodyIsPreservedForGraphQlParsing() throws Exception {
        var response = new MockHttpServletResponse();
        filter(10, 10)
                .doFilter(
                        request("{\"query\":\"x\"}"),
                        response,
                        (req, res) ->
                                assertThat(req.getInputStream().readAllBytes())
                                        .isEqualTo("{\"query\":\"x\"}".getBytes()));
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

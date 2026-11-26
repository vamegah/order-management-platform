package com.example.omp.shared.web.quota;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class QuotaTest {
    static QuotaProperties properties(boolean enabled, int port) {
        return new QuotaProperties(
                enabled, "test", "localhost", port, "", null, false, Duration.ofMillis(200), 16, 1);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledNeverTouchesStore() {
        var calls = new AtomicInteger();
        var service =
                new QuotaService(
                        (key, capacity) -> {
                            calls.incrementAndGet();
                            return 0;
                        },
                        properties(false, 6379),
                        new SimpleMeterRegistry());
        assertThat(service.acquire(QuotaPolicy.READ, "issuer", "subject").allowed()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void quotaKeysAreRedactedIndependentAndScoped() {
        var service =
                new QuotaService(
                        (key, capacity) -> 0, properties(true, 6379), new SimpleMeterRegistry());
        var keys = new HashSet<String>();
        for (int i = 0; i < 10000; i++)
            keys.add(service.key(QuotaPolicy.READ, "issuer", "sensitive-subject-" + i));
        assertThat(keys)
                .hasSize(10000)
                .allMatch(k -> !k.contains("sensitive") && !k.contains("issuer"));
        assertThat(service.key(QuotaPolicy.READ, null, "spoof"))
                .isEqualTo(service.key(QuotaPolicy.READ, null, null));
        assertThat(service.key(QuotaPolicy.READ, "issuer", " "))
                .isEqualTo(service.key(QuotaPolicy.READ, null, null));
        assertThat(service.key(QuotaPolicy.READ, "issuer", "subject"))
                .isNotEqualTo(service.key(QuotaPolicy.WRITE, "issuer", "subject"));
    }

    @Test
    void failuresAreClosedAndMetricsHaveOnlyFixedLabels() {
        var registry = new SimpleMeterRegistry();
        var service =
                new QuotaService(
                        (key, capacity) -> {
                            throw new IllegalStateException("private redis credential");
                        },
                        properties(true, 6379),
                        registry);
        assertThat(service.acquire(QuotaPolicy.WRITE, "iss", "sub"))
                .isEqualTo(new QuotaService.Decision(false, false, 1));
        assertThat(
                        registry.get("omp.quota.decisions")
                                .tags("policy", "write", "outcome", "unavailable")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void roundsRetryAndRecordsBothOutcomes() {
        var registry = new SimpleMeterRegistry();
        var queue = new ArrayDeque<Long>(List.of(0L, 1001L, 1L));
        var service =
                new QuotaService(
                        (key, capacity) -> queue.remove(), properties(true, 6379), registry);
        assertThat(service.acquire(QuotaPolicy.READ, "issuer", "subject").allowed()).isTrue();
        assertThat(service.acquire(QuotaPolicy.READ, "issuer", "subject").retryAfterSeconds())
                .isEqualTo(2);
        assertThat(service.acquire(QuotaPolicy.READ, "issuer", "subject").retryAfterSeconds())
                .isEqualTo(1);
        assertThat(registry.get("omp.quota.decisions").tag("outcome", "limited").counter().count())
                .isEqualTo(2);
    }

    @Test
    void httpUsesVerifiedIdentityAndIgnoresSpoofedHeaders() throws Exception {
        var keys = new ArrayList<String>();
        var quotas =
                new QuotaService(
                        (key, capacity) -> {
                            keys.add(key);
                            return 2001;
                        },
                        properties(true, 6379),
                        new SimpleMeterRegistry());
        var filter = new QuotaFilter(quotas);
        var request = request("GET", "/api/v1/products");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-User-Id", "admin");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a, b) -> fail("limited request reached endpoint"));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString())
                .contains("RATE_LIMITED")
                .doesNotContain("1.2.3.4");
        var problem =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(problem.has("extensions")).isFalse();
        var token =
                Jwt.withTokenValue("unused")
                        .header("alg", "RS256")
                        .issuer("https://issuer.example")
                        .subject("verified")
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a, b) -> fail("limited request reached endpoint"));
        assertThat(keys.get(1))
                .isEqualTo(quotas.key(QuotaPolicy.READ, "https://issuer.example", "verified"));
        assertThat(keys.get(0)).isEqualTo(quotas.key(QuotaPolicy.READ, null, null));
    }

    @Test
    void graphqlAndProviderRetryContractsAndStoreOutage() throws Exception {
        for (String path : List.of("/graphql", "/api/v1/payments/webhooks/stripe")) {
            var filter =
                    new QuotaFilter(
                            new QuotaService(
                                    (k, c) -> 500,
                                    properties(true, 6379),
                                    new SimpleMeterRegistry()));
            var response = new MockHttpServletResponse();
            filter.doFilter(request("POST", path), response, (a, b) -> fail("rejected"));
            assertThat(response.getStatus()).isEqualTo(path.equals("/graphql") ? 429 : 503);
            assertThat(response.getContentAsString()).contains("RATE_LIMITED");
            assertThat(response.getContentType())
                    .startsWith(
                            path.equals("/graphql")
                                    ? "application/graphql-response+json"
                                    : "application/problem+json");
        }
        var filter =
                new QuotaFilter(
                        new QuotaService(
                                (k, c) -> {
                                    throw new IllegalStateException();
                                },
                                properties(true, 6379),
                                new SimpleMeterRegistry()));
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request("POST", "/graphql"), response, (a, b) -> fail("outage must fail closed"));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("QUOTA_UNAVAILABLE");
    }

    @Test
    void healthOptionsAndUnrelatedPathsRemainAvailable() throws Exception {
        var filter =
                new QuotaFilter(
                        new QuotaService(
                                (k, c) -> {
                                    throw new IllegalStateException();
                                },
                                properties(true, 6379),
                                new SimpleMeterRegistry()));
        for (var request :
                List.of(
                        request("OPTIONS", "/api/v1/orders"),
                        request("GET", "/actuator/health"),
                        request("GET", "/v3/api-docs"))) {
            var calls = new AtomicInteger();
            filter.doFilter(
                    request, new MockHttpServletResponse(), (a, b) -> calls.incrementAndGet());
            assertThat(calls).hasValue(1);
        }
        var accepting =
                new QuotaFilter(
                        new QuotaService(
                                (k, c) -> 0, properties(true, 6379), new SimpleMeterRegistry()));
        var calls = new AtomicInteger();
        accepting.doFilter(
                request("GET", "/api/v1/products"),
                new MockHttpServletResponse(),
                (a, b) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }

    @Test
    void costClassesCoverAdministrativeAndHandshakePaths() {
        assertThat(QuotaFilter.policy(request("GET", "/api/v1/orders/admin/census")))
                .isEqualTo(QuotaPolicy.EXPENSIVE);
        assertThat(QuotaFilter.policy(request("POST", "/api/v1/payments/x/refunds")))
                .isEqualTo(QuotaPolicy.EXPENSIVE);
        assertThat(QuotaFilter.policy(request("POST", "/api/v1/orders/checkout")))
                .isEqualTo(QuotaPolicy.EXPENSIVE);
        assertThat(QuotaFilter.policy(request("POST", "/api/v1/orders")))
                .isEqualTo(QuotaPolicy.WRITE);
        assertThat(QuotaFilter.policy(request("HEAD", "/api/v1/products")))
                .isEqualTo(QuotaPolicy.READ);
        assertThat(QuotaFilter.policy(request("GET", "/ws"))).isEqualTo(QuotaPolicy.HANDSHAKE);
        assertThat(QuotaFilter.policy(request("GET", "/ws/session/websocket")))
                .isEqualTo(QuotaPolicy.HANDSHAKE);
        assertThat(QuotaPolicy.EXPENSIVE.capacity(0.01)).isEqualTo(1);
    }

    @Test
    void defaultsAreDisabledAndEnabledFilterRunsAfterSecurity() {
        var runner =
                new ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(QuotaAutoConfiguration.class));
        runner.run(
                c -> {
                    assertThat(c).hasSingleBean(QuotaService.class);
                    assertThat(c).doesNotHaveBean(QuotaFilter.class);
                    assertThat(
                                    c.getBean(QuotaService.class)
                                            .acquire(QuotaPolicy.READ, null, null)
                                            .allowed())
                            .isTrue();
                });
        runner.withPropertyValues("app.quota.enabled=true")
                .withBean(QuotaStore.class, () -> (k, n) -> 0)
                .run(
                        c -> {
                            var filter =
                                    (org.springframework.boot.web.servlet.FilterRegistrationBean<?>)
                                            c.getBean("quotaFilter");
                            assertThat(filter.getOrder()).isEqualTo(-90);
                            assertThat(filter.getUrlPatterns()).contains("/*");
                        });
    }

    @Test
    void onlyVerifiedJwtAdministratorsUseTheFiniteOperatorBudget() throws Exception {
        var keys = new ArrayList<String>();
        var filter =
                new QuotaFilter(
                        new QuotaService(
                                (key, capacity) -> {
                                    keys.add(key);
                                    return 0;
                                },
                                properties(true, 6379),
                                new SimpleMeterRegistry()));
        var jwt =
                Jwt.withTokenValue("verified")
                        .header("alg", "RS256")
                        .issuer("https://issuer.invalid")
                        .subject("operator")
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_admin"))));
        filter.doFilter(
                request("POST", "/api/v1/orders/admin/repair"),
                new MockHttpServletResponse(),
                (a, b) -> {});
        assertThat(keys.removeFirst()).contains("_OPERATOR}");
        // Even a verified administrator cannot borrow the operator allowance for callbacks.
        filter.doFilter(
                request("POST", "/api/v1/payments/webhooks/stripe"),
                new MockHttpServletResponse(),
                (a, b) -> {});
        assertThat(keys.removeFirst()).contains("_WEBHOOK}");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_user"))));
        filter.doFilter(
                request("POST", "/api/v1/orders/admin/repair"),
                new MockHttpServletResponse(),
                (a, b) -> {});
        assertThat(keys.removeFirst()).contains("_EXPENSIVE}");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                "forged",
                                "unused",
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_admin"))));
        var request = request("POST", "/api/v1/orders/admin/repair");
        request.addHeader("X-User-Role", "admin");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        filter.doFilter(request, new MockHttpServletResponse(), (a, b) -> {});
        assertThat(keys.removeFirst()).contains("_EXPENSIVE}");
    }

    @Test
    void invalidCredentialFilesCannotCreateAQuotaClient(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var password = temporary.resolve("redis-password");
        for (String value : List.of("", "x".repeat(4097))) {
            java.nio.file.Files.writeString(password, value);
            var properties =
                    new QuotaProperties(
                            true,
                            "test",
                            "localhost",
                            6379,
                            "quota",
                            password,
                            false,
                            Duration.ofMillis(100),
                            16,
                            1);
            assertThatThrownBy(() -> new RedisQuotaStore(properties))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageNotContaining("xxxx");
        }
    }

    @Test
    void startupReadinessNeverSpendsTokensAndRedactsBackendFailure() throws Exception {
        var store = org.mockito.Mockito.mock(QuotaStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("private redis credential"))
                .doNothing()
                .when(store)
                .check();
        var runner = new QuotaAutoConfiguration().quotaReadiness(store);
        runner.run(new org.springframework.boot.DefaultApplicationArguments());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .acquire(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doThrow(new IllegalStateException("private redis credential"))
                .when(store)
                .check();
        assertThatThrownBy(
                        () ->
                                runner.run(
                                        new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private")
                .hasNoCause();
    }

    @Test
    void invalidConfigurationAndMissingBackendFailClosed() throws Exception {
        assertThatThrownBy(
                        () ->
                                new QuotaProperties(
                                        true,
                                        "bad namespace",
                                        "localhost",
                                        6379,
                                        "",
                                        null,
                                        false,
                                        Duration.ofMillis(250),
                                        16,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        var configuration = new QuotaAutoConfiguration();
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var service =
                configuration.quotaService(
                        factory.getBeanProvider(QuotaStore.class),
                        properties(true, 6379),
                        factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        assertThat(service.acquire(QuotaPolicy.READ, "issuer", "user").available()).isFalse();
        var store = (QuotaStore) (key, capacity) -> 0;
        configuration
                .quotaReadiness(store)
                .run(new org.springframework.boot.DefaultApplicationArguments());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(QuotaAutoConfiguration.class))
                .withPropertyValues("app.quota.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(RedisQuotaStore.class));
        try (var digest = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
            digest.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new java.security.NoSuchAlgorithmException("missing"));
            assertThat(service.acquire(QuotaPolicy.READ, "issuer", "user").available()).isFalse();
        }
    }

    @Test
    void uppercaseAdministratorGetsTheOperatorBudgetForReadAndWrite() throws Exception {
        var jwt =
                Jwt.withTokenValue("verified")
                        .header("alg", "RS256")
                        .issuer("https://issuer.invalid")
                        .subject("admin")
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_ADMIN"))));
        var filter =
                new QuotaFilter(
                        new QuotaService(
                                (key, capacity) -> {
                                    assertThat(key).contains("_OPERATOR}");
                                    return 0;
                                },
                                properties(true, 6379),
                                new SimpleMeterRegistry()));
        for (var method : List.of("GET", "POST"))
            filter.doFilter(
                    request(method, "/api/v1/products"),
                    new MockHttpServletResponse(),
                    (a, b) -> {});
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}

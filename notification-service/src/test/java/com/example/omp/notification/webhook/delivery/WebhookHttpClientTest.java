package com.example.omp.notification.webhook.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.*;
import java.util.Map;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class WebhookHttpClientTest {
    static final java.util.concurrent.ScheduledExecutorService deadlines =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    @org.junit.jupiter.api.AfterAll
    static void closeDeadlines() {
        deadlines.shutdownNow();
    }

    @ParameterizedTest
    @CsvSource({
        "200,SUCCESS",
        "204,SUCCESS",
        "299,SUCCESS",
        "301,PERMANENT",
        "400,PERMANENT",
        "401,RETRYABLE",
        "403,RETRYABLE",
        "404,PERMANENT",
        "408,RETRYABLE",
        "410,PERMANENT",
        "422,PERMANENT",
        "429,RETRYABLE",
        "500,RETRYABLE",
        "503,RETRYABLE"
    })
    @SuppressWarnings("unchecked")
    void subscriberResponsesRetainClassification(int status, DeliveryOutcome expected)
            throws Exception {
        var transport = mock(CloseableHttpClient.class);
        var policy = mock(WebhookDestinationPolicy.class);
        when(policy.validate(anyString()))
                .thenReturn(URI.create("https://subscriber.example/hook"));
        when(transport.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(
                        call -> {
                            var request = (ClassicHttpRequest) call.getArgument(0);
                            assertThat(request.getMethod()).isEqualTo("POST");
                            assertThat(request.getFirstHeader("X-Signature-256").getValue())
                                    .isEqualTo("signed");
                            var response = new BasicClassicHttpResponse(status);
                            response.setEntity(new StringEntity("response"));
                            response.setHeader("Retry-After", "12");
                            return ((HttpClientResponseHandler<?>) call.getArgument(1))
                                    .handleResponse(response);
                        });
        var result =
                new WebhookHttpClient(transport, policy, 2, deadlines)
                        .post(
                                "https://subscriber.example/hook",
                                "{}",
                                Map.of("X-Signature-256", "signed"));
        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.httpStatus()).isEqualTo(status);
        if (expected == DeliveryOutcome.RETRYABLE)
            assertThat(result.retryAfterMillis()).isEqualTo(12000);
    }

    @Test
    void forbiddenDestinationsNeverReachTransport() {
        var transport = mock(CloseableHttpClient.class);
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        var policy = new WebhookDestinationPolicy(env, "");
        for (String url :
                java.util.List.of(
                        "http://127.0.0.1/hook",
                        "https://127.0.0.1/hook",
                        "https://[::1]/hook",
                        "https://user:pass@example.com/",
                        "file:///etc/passwd"))
            assertThat(
                            new WebhookHttpClient(transport, policy, 2, deadlines)
                                    .post(url, "{}", Map.of())
                                    .outcome())
                    .isEqualTo(DeliveryOutcome.PERMANENT);
        verifyNoInteractions(transport);
    }

    @Test
    void realTransportRefusesRedirectAndBoundsResponseBodies() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var escaped = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(
                "/redirect",
                e -> {
                    e.getResponseHeaders().set("Location", "/forbidden");
                    e.sendResponseHeaders(302, -1);
                    e.close();
                });
        server.createContext(
                "/forbidden",
                e -> {
                    escaped.incrementAndGet();
                    e.sendResponseHeaders(204, -1);
                    e.close();
                });
        server.createContext(
                "/large",
                e -> {
                    e.sendResponseHeaders(200, 1000000);
                    try {
                        e.getResponseBody().write(new byte[1000000]);
                    } catch (Exception ignored) {
                    } finally {
                        e.close();
                    }
                });
        server.start();
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        var policy = new WebhookDestinationPolicy(env, "localhost");
        try (var transport =
                new com.example.omp.notification.webhook.config.WebhookHttpClientConfig()
                        .webhookTransport(policy)) {
            var client = new WebhookHttpClient(transport, policy, 2, deadlines);
            String base = "http://localhost:" + server.getAddress().getPort();
            assertThat(client.post(base + "/redirect", "{}", Map.of()).outcome())
                    .isEqualTo(DeliveryOutcome.PERMANENT);
            assertThat(escaped).hasValue(0);
            var result = client.post(base + "/large", "{}", Map.of());
            assertThat(result.responseBody()).hasSize(4096);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retryAfterSupportsDatesAndDoesNotOverflow() {
        Instant now = Instant.parse("2026-11-15T00:00:00Z");
        assertThat(WebhookHttpClient.retryAfter("Sun, 15 Nov 2026 00:01:00 GMT", now))
                .isEqualTo(60000);
        assertThat(WebhookHttpClient.retryAfter("9999999999", now)).isEqualTo(86400000);
        assertThat(WebhookHttpClient.retryAfter("-1", now)).isNull();
        assertThat(WebhookHttpClient.retryAfter("invalid", now)).isNull();
    }
}

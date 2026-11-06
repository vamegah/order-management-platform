package com.example.omp.notification.webhook.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.*;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class WebhookHttpClientTest {
    final HttpClient transport = mock(HttpClient.class);
    final WebhookHttpClient client = new WebhookHttpClient(transport, 10);

    @SuppressWarnings("unchecked")
    final HttpResponse<String> response = mock(HttpResponse.class);

    void responds(int status, String body, Map<String, java.util.List<String>> headers)
            throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (key, value) -> true));
        doReturn(response).when(transport).send(any(), any());
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
    void classifiesSubscriberStatusWithoutFollowingRedirects(int status, DeliveryOutcome outcome)
            throws Exception {
        responds(status, "response", Map.of());
        var result = client.post("https://subscriber.example/hook", "{}", Map.of());
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.httpStatus()).isEqualTo(status);
    }

    @Test
    void postCarriesRawBodySignatureHeadersTimeoutAndTruncatedAuditResponse() throws Exception {
        responds(429, "x".repeat(5000), Map.of("Retry-After", java.util.List.of("12")));
        var result =
                client.post(
                        "https://subscriber.example/hook",
                        "{\"value\":1}",
                        Map.of("X-Signature-256", "sha256=test"));
        assertThat(result.retryAfterMillis()).isEqualTo(12_000L);
        assertThat(result.responseBody()).hasSize(4096);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(request.capture(), any());
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(10));
        assertThat(request.getValue().headers().firstValue("X-Signature-256"))
                .contains("sha256=test");
        assertThat(request.getValue().headers().firstValue("Content-Type"))
                .contains("application/json; charset=utf-8");
    }

    @Test
    void invalidRetryAfterDoesNotPreventRetryClassification() throws Exception {
        responds(408, null, Map.of("Retry-After", java.util.List.of("not-seconds")));
        var result = client.post("https://subscriber.example/hook", "{}", Map.of());
        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.retryAfterMillis()).isNull();
    }

    @Test
    void configuredTimeoutBoundsTheRequest() throws Exception {
        responds(200, "ok", Map.of());
        new WebhookHttpClient(transport, 2).post("https://subscriber.example/hook", "{}", Map.of());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(request.capture(), any());
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1, 61, Long.MAX_VALUE})
    void invalidTimeoutFailsAtStartup(long seconds) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookHttpClient(transport, seconds));
        verifyNoInteractions(transport);
    }

    static java.util.List<Exception> transportFailures() {
        return java.util.List.of(
                new HttpTimeoutException("timed out"),
                new IOException("connection reset"),
                new SSLHandshakeException("untrusted certificate"),
                new IllegalStateException("transport closed"));
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void classifiesTransportFailures(Exception failure) throws Exception {
        doThrow(failure).when(transport).send(any(), any());
        var result = client.post("https://subscriber.example/hook", "{}", Map.of());
        assertThat(result.outcome())
                .isEqualTo(
                        failure instanceof SSLHandshakeException
                                ? DeliveryOutcome.PERMANENT
                                : DeliveryOutcome.RETRYABLE);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void interruptedSendPreservesThreadInterruption() throws Exception {
        doThrow(new InterruptedException()).when(transport).send(any(), any());
        try {
            assertThat(client.post("https://subscriber.example/hook", "{}", Map.of()).outcome())
                    .isEqualTo(DeliveryOutcome.RETRYABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidUrlNeverUsesTransport() {
        assertThat(client.post("not a uri", "{}", Map.of()).outcome())
                .isEqualTo(DeliveryOutcome.PERMANENT);
        verifyNoInteractions(transport);
    }
}

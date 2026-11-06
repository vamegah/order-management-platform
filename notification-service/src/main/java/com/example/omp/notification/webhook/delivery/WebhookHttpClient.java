package com.example.omp.notification.webhook.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP client wrapper for outbound webhook delivery.
 *
 * <p>- 10s connect timeout and configurable request timeout (default 10s) - Does not follow
 * redirects (a redirect to a different host is usually a misconfiguration) - Reuses connections
 * (HttpClient is thread-safe and connection-pooled) - Classifies transport-level failures into
 * DeliveryResult
 */
@Component
public class WebhookHttpClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookHttpClient.class);
    private final HttpClient httpClient;
    private final Duration timeout;

    public WebhookHttpClient(
            HttpClient httpClient,
            @Value("${webhook.delivery.timeout-seconds:10}") long timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException(
                    "webhook.delivery.timeout-seconds must be between 1 and 60");
        }
        this.httpClient = httpClient;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public DeliveryResult post(String url, String rawBody, Map<String, String> headers) {
        HttpRequest.Builder builder;
        try {
            builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(timeout)
                            .POST(HttpRequest.BodyPublishers.ofString(rawBody));
        } catch (IllegalArgumentException e) {
            return DeliveryResult.permanentError("Invalid URL: " + e.getMessage());
        }

        headers.forEach(builder::header);
        builder.header("Content-Type", "application/json; charset=utf-8");

        HttpRequest request = builder.build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return classify(response);
        } catch (HttpTimeoutException e) {
            return DeliveryResult.retryableError("Timeout after " + timeout.toSeconds() + "s");
        } catch (SSLHandshakeException e) {
            return DeliveryResult.permanentError("TLS handshake failed: " + e.getMessage());
        } catch (IOException e) {
            return DeliveryResult.retryableError("I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.retryableError("Interrupted");
        } catch (RuntimeException e) {
            log.warn("Unexpected error during webhook POST", e);
            return DeliveryResult.retryableError("Unexpected error: " + e.getMessage());
        }
    }

    private DeliveryResult classify(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = truncate(response.body());

        if (status >= 200 && status < 300) {
            return DeliveryResult.success(status, body);
        }
        if (status == 408 || status == 429) {
            Long retryAfter = retryAfterMillis(response).orElse(null);
            return DeliveryResult.retryable(status, body, retryAfter);
        }
        if (status == 401 || status == 403) {
            // Subscriber auth misconfig — retryable: they may fix it in the window.
            return DeliveryResult.retryable(status, body);
        }
        if (status >= 500) {
            return DeliveryResult.retryable(status, body);
        }
        // Remaining 4xx (400, 404, 410, 422, ...) -> permanent.
        return DeliveryResult.permanent(status, body);
    }

    private Optional<Long> retryAfterMillis(HttpResponse<String> response) {
        return response.headers()
                .firstValue("Retry-After")
                .flatMap(
                        value -> {
                            try {
                                // Only the delta-seconds form is supported.
                                long seconds = Long.parseLong(value.trim());
                                return Optional.of(seconds * 1000L);
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        });
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= 4096 ? body : body.substring(0, 4096);
    }
}

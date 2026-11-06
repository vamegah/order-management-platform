package com.example.omp.payment.provider.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Minimal Stripe REST client.
 *
 * <p>Deliberately no SDK: - one fewer transitive dependency to audit - the wire surface we use is
 * small (create PaymentIntent, create Refund) - the exact request/response shape is visible here
 *
 * <p>Stripe's API is form-encoded (application/x-www-form-urlencoded) with bracket notation for
 * nested parameters (e.g. metadata[order_id]=...).
 */
@Component
public class StripeApiClient {

    private static final Logger log = LoggerFactory.getLogger(StripeApiClient.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final StripeProperties properties;
    private final String baseUrl;

    @Autowired
    public StripeApiClient(
            @Qualifier("stripeHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            StripeProperties properties) {
        this(httpClient, objectMapper, properties, "https://api.stripe.com/v1");
    }

    public StripeApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            StripeProperties properties,
            String baseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    public JsonNode createPaymentIntent(Map<String, String> params) {
        return post("/payment_intents", params);
    }

    public JsonNode createRefund(Map<String, String> params, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[!-~]{1,255}"))
            throw new IllegalArgumentException("Refund idempotency key is required");
        return post("/refunds", params, idempotencyKey);
    }

    public JsonNode getPaymentIntent(String id) {
        return get("/payment_intents/" + id);
    }

    public JsonNode getRefund(String id) {
        if (id == null || !id.matches("re_[A-Za-z0-9_]{1,240}"))
            throw new IllegalArgumentException("Invalid provider refund reference");
        return get("/refunds/" + id);
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    private JsonNode post(String path, Map<String, String> params) {
        return post(path, params, null);
    }

    private JsonNode post(String path, Map<String, String> params, String idempotencyKey) {
        String body = encodeForm(params);
        HttpRequest.Builder builder = baseRequest(path);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        HttpRequest request =
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return send(request);
    }

    private JsonNode get(String path) {
        HttpRequest request = baseRequest(path).GET().build();
        return send(request);
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Stripe-Version", properties.getApiVersion())
                .header("Accept", "application/json");
    }

    private JsonNode send(HttpRequest request) {
        int attempts = 0;
        int maxAttempts = properties.getMaxNetworkRetries() + 1;
        IOException lastIo = null;

        while (attempts < maxAttempts) {
            attempts++;
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                JsonNode node = parse(response.body());

                if (status >= 200 && status < 300) {
                    return node;
                }
                if (status >= 500 || status == 429) {
                    // Retryable at the HTTP level. Back off briefly.
                    log.warn(
                            "Stripe returned {} (attempt {}/{}): {}",
                            status,
                            attempts,
                            maxAttempts,
                            response.body());
                    if (attempts < maxAttempts) {
                        sleepBackoff(attempts);
                        continue;
                    }
                }

                // 4xx (other than 429) -> caller receives the error body.
                throw new StripeApiException(status, errorMessage(node), node);
            } catch (HttpTimeoutException e) {
                lastIo = e;
                log.warn("Stripe request timed out (attempt {}/{})", attempts, maxAttempts);
                if (attempts < maxAttempts) {
                    sleepBackoff(attempts);
                    continue;
                }
            } catch (IOException e) {
                lastIo = e;
                log.warn(
                        "Stripe I/O error (attempt {}/{}): {}",
                        attempts,
                        maxAttempts,
                        e.getMessage());
                if (attempts < maxAttempts) {
                    sleepBackoff(attempts);
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StripeApiException(0, "Interrupted", null);
            }
        }

        throw new StripeApiException(
                0,
                "Stripe unreachable after "
                        + maxAttempts
                        + " attempts: "
                        + (lastIo != null ? lastIo.getMessage() : "unknown"),
                null);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (IOException e) {
            throw new StripeApiException(0, "Unparseable Stripe response: " + e.getMessage(), null);
        }
    }

    private static String errorMessage(JsonNode node) {
        if (node == null) return "unknown error";
        JsonNode error = node.get("error");
        if (error != null && error.has("message")) {
            return error.get("message").asText();
        }
        return node.toString();
    }

    private static String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleepBackoff(int attempt) {
        long millis = (long) (Math.pow(2, attempt - 1) * 200);
        long jitter = (long) (millis * 0.1 * (Math.random() * 2 - 1));
        try {
            Thread.sleep(Math.max(0, millis + jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Convenience for callers that want an ordered parameter map. */
    public static Map<String, String> params() {
        return new LinkedHashMap<>();
    }
}

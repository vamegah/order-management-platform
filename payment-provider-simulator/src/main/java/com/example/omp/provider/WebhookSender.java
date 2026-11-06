package com.example.omp.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends signed webhooks to the configured callback URL.
 *
 * <p><b>Signing format:</b>
 *
 * <pre>
 *   signature = HMAC-SHA256(secret, timestamp + "." + raw_body)
 * </pre>
 *
 * The signature is sent in the {@code X-Signature-256} header as {@code sha256=<hex>}. The
 * timestamp is sent in {@code X-Timestamp}.
 *
 * <p><b>Headers sent:</b>
 *
 * <ul>
 *   <li>{@code Content-Type: application/json}
 *   <li>{@code X-Webhook-Id} — the event's unique ID (used by payment-service for idempotency).
 *   <li>{@code X-Webhook-Event} — the event type (used by payment-service for routing without
 *       parsing the body).
 *   <li>{@code X-Timestamp} — Unix seconds when the webhook was signed.
 *   <li>{@code X-Signature-256} — HMAC-SHA256 signature in hex.
 * </ul>
 *
 * <p><b>Threading:</b> the simulator sends each webhook after a configurable delay without blocking
 * the caller. This is implemented via the caller (the controller) scheduling the send on a separate
 * thread. The sender itself is synchronous — it just serializes, signs, and POSTs.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final SimulatorProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;

    public WebhookSender(
            SimulatorProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            MeterRegistry metrics) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Signs and sends a webhook to the configured URL.
     *
     * @param payload the event payload to send
     * @return true if the receiver returned 2xx, false otherwise
     */
    public boolean send(WebhookEventPayload payload) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize webhook payload {}", payload.id(), e);
            metrics.counter("simulator.webhook.send.errors", "reason", "serialization").increment();
            return false;
        }

        String timestamp = Long.toString(payload.created());
        String signature = computeSignature(timestamp, body);

        log.info(
                "Sending webhook id={} type={} to {}",
                payload.id(),
                payload.type(),
                properties.getWebhookUrl());

        try {
            restClient
                    .post()
                    .uri(properties.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Webhook-Id", payload.id())
                    .header("X-Webhook-Event", payload.type())
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature-256", "sha256=" + signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Webhook id={} delivered successfully", payload.id());
            metrics.counter("simulator.webhook.send.success", "event_type", payload.type())
                    .increment();
            return true;

        } catch (Exception e) {
            log.warn("Webhook id={} delivery failed: {}", payload.id(), e.getMessage());
            metrics.counter("simulator.webhook.send.errors", "reason", "delivery").increment();
            return false;
        }
    }

    /**
     * Computes the HMAC-SHA256 signature over {@code timestamp + "." + body}.
     *
     * <p>Both the sender (this class) and the receiver (payment-service) use the exact same
     * formula. Any divergence causes signature verification to fail loudly at the receiver.
     */
    private String computeSignature(String timestamp, String body) {
        String message = timestamp + "." + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key =
                    new SecretKeySpec(
                            properties.getHmacSecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // HmacSHA256 is guaranteed to exist on every JVM. This branch
            // is unreachable in practice, but checked exceptions require
            // handling.
            throw new IllegalStateException("HmacSHA256 algorithm not available", e);
        }
    }
}

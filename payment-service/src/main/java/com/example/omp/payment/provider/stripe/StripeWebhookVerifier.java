package com.example.omp.payment.provider.stripe;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies Stripe webhook signatures.
 *
 * <p>Stripe sends: Stripe-Signature: t=<unix_seconds>,v1=<hex_hmac>[,v0=<...>]
 *
 * <p>The signed payload is: <t>.<rawBody> The signature is: HMAC-SHA256(webhookSecret,
 * signedPayload)
 *
 * <p>We: - parse the header - reject if t is missing or outside the tolerance window -
 * constant-time compare v1 signatures - accept if any v1 matches (Stripe may send multiple during
 * key rotation)
 */
@Component
public class StripeWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookVerifier.class);

    private final StripeProperties properties;

    public StripeWebhookVerifier(StripeProperties properties) {
        this.properties = properties;
    }

    public VerificationResult verify(String rawBody, Map<String, String> headers) {
        String header = findHeader(headers, "Stripe-Signature");
        if (header == null || header.isBlank()) {
            return VerificationResult.invalid("missing_stripe_signature");
        }

        Long timestamp = null;
        java.util.List<String> v1Signatures = new java.util.ArrayList<>();

        for (String part : header.split(",")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            switch (key) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return VerificationResult.invalid("bad_timestamp");
                    }
                }
                case "v1" -> v1Signatures.add(value);
                default -> {
                    /* v0 or unknown scheme; ignore */ }
            }
        }

        if (timestamp == null) {
            return VerificationResult.invalid("missing_timestamp");
        }
        if (v1Signatures.isEmpty()) {
            return VerificationResult.invalid("missing_v1_signature");
        }

        long now = System.currentTimeMillis() / 1000L;
        long tolerance = properties.getWebhookToleranceSeconds();
        if (timestamp < 0
                || tolerance < 0
                || (timestamp > now ? timestamp - now : now - timestamp) > tolerance) {
            log.warn(
                    "Stripe webhook timestamp {} outside tolerance window (now={})",
                    timestamp,
                    now);
            return VerificationResult.invalid("timestamp_out_of_tolerance");
        }

        String expected = computeSignature(timestamp, rawBody);

        boolean anyMatch = false;
        for (String provided : v1Signatures) {
            if (constantTimeEquals(expected, provided)) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            return VerificationResult.invalid("signature_mismatch");
        }
        return VerificationResult.valid(timestamp);
    }

    private String computeSignature(long timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            String signedPayload = timestamp + "." + rawBody;
            byte[] digest = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute Stripe webhook signature", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String findHeader(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public record VerificationResult(boolean valid, String reason, Long timestamp) {
        public static VerificationResult valid(long timestamp) {
            return new VerificationResult(true, null, timestamp);
        }

        public static VerificationResult invalid(String reason) {
            return new VerificationResult(false, reason, null);
        }
    }
}

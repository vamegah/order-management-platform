package com.example.omp.notification.webhook.delivery;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs outbound webhook payloads with HMAC-SHA256.
 *
 * <p>Wire format (same scheme as inbound, Batch 9.2): X-Signature-256: sha256=<hex> X-Timestamp:
 * <unix_seconds>
 *
 * <p>signature = HMAC-SHA256(subscriber_secret, timestamp + "." + raw_body)
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    public String sign(String secret, long timestampSeconds, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String signedPayload = timestampSeconds + "." + rawBody;
            byte[] digest = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }

    public boolean verify(
            String secret, long timestampSeconds, String rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        String expected = sign(secret, timestampSeconds, rawBody);
        return constantTimeEquals(expected, signatureHeader);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}

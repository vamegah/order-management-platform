package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.WebhookSubscription;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Subscriber-side signature verification.
 *
 * <p>This test simulates the subscriber's perspective: it uses the subscription secret to
 * independently verify the HMAC over (timestamp + "." + body) and asserts that: 1. The signature
 * matches with the correct secret. 2. It does NOT match with a different secret. 3. A tampered body
 * invalidates the signature. 4. A tampered timestamp invalidates the signature.
 *
 * <p>This is the same verification that a real subscriber is expected to perform (documented in
 * Batch 9.2's subscriber integration guide).
 */
class OutboundWebhookSignatureVerificationIT extends OutboundWebhookBase {

    @Test
    void signature_verifies_with_correct_secret() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(200);

        publishEvent(
                "payments.events.payment",
                "evt-sig-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-sig\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        String body = subscriber.bodies.get(0);
        String sigHeader = subscriber.header(0, "X-Signature-256");
        long ts = Long.parseLong(subscriber.header(0, "X-Timestamp"));

        assertThat(computeHmac(sub.getSecret(), ts, body))
                .as("subscriber-computed HMAC equals header")
                .isEqualTo(sigHeader.substring("sha256=".length()));
    }

    @Test
    void signature_fails_with_wrong_secret() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(200);

        publishEvent(
                "payments.events.payment",
                "evt-sig-wrong-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-sig-wrong\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        String body = subscriber.bodies.get(0);
        String sigHeader = subscriber.header(0, "X-Signature-256");
        long ts = Long.parseLong(subscriber.header(0, "X-Timestamp"));

        String wrong = computeHmac("wrong-secret-wrong-secret-wrong-secret", ts, body);
        assertThat(wrong).isNotEqualTo(sigHeader.substring("sha256=".length()));
    }

    @Test
    void signature_fails_when_body_tampered() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(200);

        publishEvent(
                "payments.events.payment",
                "evt-sig-tamper-" + java.util.UUID.randomUUID(),
                "payment.confirmed",
                "{\"paymentId\":\"p-tamper\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        String body = subscriber.bodies.get(0);
        String sigHeader = subscriber.header(0, "X-Signature-256");
        long ts = Long.parseLong(subscriber.header(0, "X-Timestamp"));

        // Tamper with the body: flip a character.
        String tamperedBody = body.replace("payment.confirmed", "payment.failed");
        assertThat(tamperedBody).isNotEqualTo(body);
        assertThat(computeHmac(sub.getSecret(), ts, tamperedBody))
                .isNotEqualTo(sigHeader.substring("sha256=".length()));
    }

    @Test
    void signature_fails_when_timestamp_changed() throws Exception {
        WebhookSubscription sub = createSubscription("order.placed");
        subscriber.enqueueResponse(200);

        publishEvent(
                "orders.events.order",
                "evt-sig-ts-" + java.util.UUID.randomUUID(),
                "order.placed",
                "{\"orderId\":\"o-sig\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        String body = subscriber.bodies.get(0);
        String sigHeader = subscriber.header(0, "X-Signature-256");
        long ts = Long.parseLong(subscriber.header(0, "X-Timestamp"));

        assertThat(computeHmac(sub.getSecret(), ts + 1, body))
                .isNotEqualTo(sigHeader.substring("sha256=".length()));
    }

    // --- helpers ---

    private static String computeHmac(String secret, long ts, String body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest =
                mac.doFinal((ts + "." + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}

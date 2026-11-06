package com.example.omp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.payment.provider.stripe.StripeProperties;
import com.example.omp.payment.provider.stripe.StripeWebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class StripeWebhookVerifierTest {

    @Test
    void rejectsExtremeTimestampsWithoutArithmeticOverflow() {
        StripeProperties properties = new StripeProperties();
        properties.setWebhookSecret(SECRET);
        var verifier = new StripeWebhookVerifier(properties);
        for (long timestamp : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1}) {
            var result =
                    verifier.verify(
                            "{}", Map.of("Stripe-Signature", "t=" + timestamp + ",v1=invalid"));
            assertThat(result.valid()).isFalse();
            assertThat(result.reason()).isEqualTo("timestamp_out_of_tolerance");
        }
    }

    private static final String SECRET = "whsec_test_secret";

    @Test
    void accepts_valid_signature() throws Exception {
        StripeProperties props = new StripeProperties();
        props.setWebhookSecret(SECRET);
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(props);

        String body = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}";
        long ts = System.currentTimeMillis() / 1000L;
        String sig = sign(ts, body, SECRET);

        var result = verifier.verify(body, Map.of("Stripe-Signature", "t=" + ts + ",v1=" + sig));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejects_when_timestamp_too_old() throws Exception {
        StripeProperties props = new StripeProperties();
        props.setWebhookSecret(SECRET);
        props.setWebhookToleranceSeconds(60);
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(props);

        String body = "{}";
        long ts = System.currentTimeMillis() / 1000L - 3600;
        String sig = sign(ts, body, SECRET);

        var result = verifier.verify(body, Map.of("Stripe-Signature", "t=" + ts + ",v1=" + sig));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("timestamp_out_of_tolerance");
    }

    @Test
    void rejects_when_signature_wrong() {
        StripeProperties props = new StripeProperties();
        props.setWebhookSecret(SECRET);
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(props);

        long ts = System.currentTimeMillis() / 1000L;
        var result = verifier.verify("{}", Map.of("Stripe-Signature", "t=" + ts + ",v1=deadbeef"));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("signature_mismatch");
    }

    private static String sign(long ts, String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}

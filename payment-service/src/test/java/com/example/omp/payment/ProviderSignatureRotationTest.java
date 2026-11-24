package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.provider.stripe.*;
import com.example.omp.payment.support.ProviderSignatures;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderSignatureRotationTest {
    @TempDir Path directory;

    @Test
    void currentAndPreviousMountedKeysOverlapThenRetireWithoutRestart() throws Exception {
        Path current = directory.resolve("current"), previous = directory.resolve("previous");
        Files.writeString(current, "new-signing-key");
        Files.writeString(previous, "old-signing-key");
        var properties = new StripeProperties();
        properties.setWebhookSecretFile(current.toString());
        properties.setPreviousWebhookSecretFile(previous.toString());
        properties.setPreviousWebhookSecretValidUntil(Instant.now().plusSeconds(60));
        var verifier = new StripeWebhookVerifier(properties);
        long timestamp = Instant.now().getEpochSecond();
        String old = ProviderSignatures.sign("{}", "old-signing-key", timestamp),
                fresh = ProviderSignatures.sign("{}", "new-signing-key", timestamp);
        assertThat(verifier.verify("{}", Map.of("Stripe-Signature", old)).valid()).isTrue();
        assertThat(verifier.verify("{}", Map.of("Stripe-Signature", fresh)).valid()).isTrue();
        properties.setPreviousWebhookSecretValidUntil(Instant.now().minusSeconds(1));
        assertThat(verifier.verify("{}", Map.of("Stripe-Signature", old)).valid()).isFalse();
        Files.writeString(current, "replacement-key");
        assertThat(verifier.verify("{}", Map.of("Stripe-Signature", fresh)).valid()).isFalse();
        assertThat(
                        verifier.verify(
                                        "{}",
                                        Map.of(
                                                "Stripe-Signature",
                                                ProviderSignatures.sign(
                                                        "{}", "replacement-key", timestamp)))
                                .valid())
                .isTrue();
    }

    @Test
    void ambiguousOrExcessiveHeadersFailClosed() {
        var properties = new StripeProperties();
        properties.setWebhookSecret("key");
        var verifier = new StripeWebhookVerifier(properties);
        long timestamp = Instant.now().getEpochSecond();
        String signed = ProviderSignatures.sign("{}", "key", timestamp);
        assertThat(
                        verifier.verify(
                                        "{}",
                                        Map.of("Stripe-Signature", signed + ",t=" + timestamp))
                                .valid())
                .isFalse();
        assertThat(verifier.verify("{}", Map.of("Stripe-Signature", "x".repeat(3000))).valid())
                .isFalse();
        assertThat(verifier.verify("{} ", Map.of("Stripe-Signature", signed)).valid()).isFalse();
    }
}

package com.example.omp.payment.provider.noop;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoopPaymentProviderTest {
    private final NoopPaymentProvider provider = new NoopPaymentProvider(new ObjectMapper());

    @Test
    void successfulChargeReplayReturnsTheOriginalProviderReference() {
        var request =
                new ChargeRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1550,
                        "USD",
                        "successful-charge",
                        "test");

        var first = provider.charge(request);

        assertThat(first.status()).isEqualTo(ProviderChargeResult.Status.SUCCEEDED);
        assertThat(first.providerReference()).isNotBlank();
        assertThat(provider.charge(request)).isEqualTo(first);
    }

    @Test
    void simulatedDeclineIsDistinctFromARetryableProviderError() {
        var request =
                new ChargeRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1500,
                        "USD",
                        "declined-charge",
                        "test");

        var result = provider.charge(request);

        assertThat(result.status()).isEqualTo(ProviderChargeResult.Status.DECLINED);
        assertThat(result.declineCode()).isEqualTo("card_declined");
        assertThat(result.providerReference()).isNull();
    }

    @Test
    void refundReplayAndLookupUseTheSameReferenceWithoutInventingMissingRefunds() {
        var request =
                new RefundRequest(UUID.randomUUID(), "pi", 500, "USD", "refund-key", "cancelled");
        var first = provider.refund(request);
        assertThat(first.status()).isEqualTo(ProviderRefundResult.Status.SUCCEEDED);
        assertThat(provider.refund(request)).isEqualTo(first);
        assertThat(provider.lookupRefund(request, first.providerReference())).isEqualTo(first);
        assertThat(provider.lookupRefund(request, "wrong").status())
                .isEqualTo(ProviderRefundResult.Status.ERROR);
        assertThat(
                        provider.lookupRefund(
                                        new RefundRequest(
                                                request.paymentId(),
                                                "pi",
                                                500,
                                                "USD",
                                                "unknown",
                                                null),
                                        "missing")
                                .status())
                .isEqualTo(ProviderRefundResult.Status.ERROR);
    }

    @Test
    void simulatedProviderErrorsRemainRetryable() {
        var request =
                new ChargeRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1599,
                        "USD",
                        "retryable",
                        "test");
        assertThat(provider.charge(request).status()).isEqualTo(ProviderChargeResult.Status.ERROR);
    }

    @Test
    void signatureHeaderIsCaseInsensitiveAndParsesTheOriginalProviderReference() {
        var result =
                provider.verifyWebhook(
                        "{\"id\":\"evt\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"payment_intent\":\"pi\"}}}",
                        Map.of("x-noop-signature", "noop-dev-secret"));
        assertThat(result.valid()).isTrue();
        assertThat(result.event().paymentRef()).isEqualTo("pi");
        var fallback =
                provider.verifyWebhook(
                        "{\"id\":\"evt2\",\"type\":\"updated\",\"data\":{\"object\":{\"id\":\"pi_fallback\"}}}",
                        Map.of("X-Noop-Signature", "noop-dev-secret"));
        assertThat(fallback.event().paymentRef()).isEqualTo("pi_fallback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":\"evt\"}", "{\"type\":\"event\"}", "not-json"})
    void authenticatedButMalformedEventsAreRejected(String body) {
        assertThat(
                        provider.verifyWebhook(body, Map.of("X-Noop-Signature", "noop-dev-secret"))
                                .valid())
                .isFalse();
    }

    @Test
    void missingAndWrongSignaturesAreRejectedBeforeParsing() {
        assertThat(provider.verifyWebhook("not-json", null).reason())
                .isEqualTo("bad_noop_signature");
        assertThat(provider.verifyWebhook("not-json", Map.of("X-Noop-Signature", "wrong")).valid())
                .isFalse();
    }
}

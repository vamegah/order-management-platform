package com.example.omp.payment.config;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.provider.noop.NoopPaymentProvider;
import com.example.omp.payment.provider.stripe.StripeProperties;
import com.example.omp.payment.provider.stripe.StripeWebhookVerifier;
import com.example.omp.payment.web.StripeWebhookController;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Public authentication metadata must work with the actual provider verifiers. */
class PublishedProviderSecurityTest {
    private static final String BODY =
            "{\"id\":\"evt_contract\",\"type\":\"contract.ignored\",\"data\":{\"object\":{\"id\":\"reference\"}}}";

    @Test
    void publishedNoopHeaderAuthenticatesWhileTheRootJwtSchemeDoesNot() {
        var document = new OpenApiConfig().publishedOpenApi();
        var header = document.getComponents().getSecuritySchemes().get("noopSignature");
        var verifier = new NoopPaymentProvider(new ObjectMapper());
        assertThat(
                        verifier.verifyWebhook(BODY, Map.of(header.getName(), "noop-dev-secret"))
                                .valid())
                .isTrue();
        assertThat(
                        verifier.verifyWebhook(
                                        BODY, Map.of("Authorization", "Bearer noop-dev-secret"))
                                .valid())
                .isFalse();
        assertThat(
                        Arrays.stream(
                                        StripeWebhookController.class.getAnnotationsByType(
                                                SecurityRequirement.class))
                                .map(SecurityRequirement::name))
                .containsExactlyInAnyOrder("noopSignature", "providerSignature");
    }

    @Test
    void publishedStripeHeaderAuthenticatesTheOriginalSignedBytes() throws Exception {
        var document = new OpenApiConfig().publishedOpenApi();
        var header = document.getComponents().getSecuritySchemes().get("providerSignature");
        var properties = new StripeProperties();
        properties.setWebhookSecret("unit-contract-signing-key");
        properties.setWebhookToleranceSeconds(300);
        long timestamp = System.currentTimeMillis() / 1000L;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec(
                        properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"));
        String signature =
                "t="
                        + timestamp
                        + ",v1="
                        + HexFormat.of()
                                .formatHex(
                                        mac.doFinal(
                                                (timestamp + "." + BODY)
                                                        .getBytes(StandardCharsets.UTF_8)));
        var verifier = new StripeWebhookVerifier(properties);
        assertThat(verifier.verify(BODY, Map.of(header.getName(), signature)).valid()).isTrue();
        assertThat(verifier.verify(BODY + " ", Map.of(header.getName(), signature)).valid())
                .isFalse();
        assertThat(verifier.verify(BODY, Map.of("Authorization", "Bearer " + signature)).valid())
                .isFalse();
    }
}

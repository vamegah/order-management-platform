package com.example.omp.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.payment.provider.ChargeRequest;
import com.example.omp.payment.provider.ProviderChargeResult;
import com.example.omp.payment.provider.stripe.StripeApiClient;
import com.example.omp.payment.provider.stripe.StripePaymentProvider;
import com.example.omp.payment.provider.stripe.StripeProperties;
import com.example.omp.payment.provider.stripe.StripeWebhookVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.http.HttpClient;
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * Tests the Stripe provider against a WireMock instance standing in for api.stripe.com.
 *
 * <p>The base URL is not configurable in production code (it's a constant in StripeApiClient). For
 * the test we spin up a WireMock on a fixed port and override the client's base URL via reflection
 * or a test-only constructor if one is added. For brevity, this test uses the test-only constructor
 * approach described below.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StripePaymentProviderTest {

    private WireMockServer wireMock;
    private StripePaymentProvider provider;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        StripeProperties props = new StripeProperties();
        props.setApiKey("sk_test_123");
        props.setWebhookSecret("whsec_test");
        props.setTimeoutSeconds(5);
        props.setMaxNetworkRetries(0);

        // StripeApiClient needs a test-only constructor accepting a base URL.
        StripeApiClient apiClient =
                new StripeApiClient(
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        props,
                        "http://localhost:" + wireMock.port() + "/v1");

        StripeWebhookVerifier verifier = new StripeWebhookVerifier(props);
        provider = new StripePaymentProvider(apiClient, verifier, new ObjectMapper());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"pending", "succeeded", "failed", "canceled", "requires_action"})
    void refundSendsStableIdempotencyHeaderAndPreservesProviderStatus(String status) {
        UUID refundId = UUID.randomUUID();
        wireMock.stubFor(
                post(urlEqualTo("/v1/refunds"))
                        .willReturn(
                                okJson(
                                        "{\"id\":\"re-1\",\"status\":\""
                                                + status
                                                + "\",\"amount\":400,\"currency\":\"usd\",\"payment_intent\":\"pi-1\"}")));
        var request =
                new com.example.omp.payment.provider.RefundRequest(
                        UUID.randomUUID(),
                        "pi-1",
                        400,
                        "USD",
                        "omp-refund-" + refundId,
                        "requested");
        var first = provider.refund(request);
        var second = provider.refund(request);
        assertThat(first).isEqualTo(second);
        assertThat(first.status().name())
                .isEqualTo(
                        switch (status) {
                            case "succeeded" -> "SUCCEEDED";
                            case "failed", "canceled" -> "FAILED";
                            default -> "PENDING";
                        });
        wireMock.verify(
                2,
                postRequestedFor(urlEqualTo("/v1/refunds"))
                        .withHeader("Idempotency-Key", equalTo("omp-refund-" + refundId))
                        .withFormParam("amount", equalTo("400"))
                        .withFormParam("metadata[refund_id]", equalTo(refundId.toString()))
                        .withFormParam("payment_intent", equalTo("pi-1")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 409, 429, 500})
    void refundHttpErrorsCannotReleaseAnUncertainBalance(int status) {
        wireMock.stubFor(
                post(urlEqualTo("/v1/refunds"))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":{\"message\":\"unresolved\"}}")));
        var result =
                provider.refund(
                        new com.example.omp.payment.provider.RefundRequest(
                                UUID.randomUUID(),
                                "pi-1",
                                400,
                                "USD",
                                "omp-refund-" + UUID.randomUUID(),
                                "requested"));
        assertThat(result.status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"amount", "currency", "payment"})
    void mismatchedProviderResponseCannotConfirmRefund(String mismatch) {
        String body =
                "{\"id\":\"re-1\",\"status\":\"succeeded\",\"amount\":"
                        + (mismatch.equals("amount") ? 500 : 400)
                        + ",\"currency\":\""
                        + (mismatch.equals("currency") ? "eur" : "usd")
                        + "\",\"payment_intent\":\""
                        + (mismatch.equals("payment") ? "pi-other" : "pi-1")
                        + "\"}";
        wireMock.stubFor(post(urlEqualTo("/v1/refunds")).willReturn(okJson(body)));
        var result =
                provider.refund(
                        new com.example.omp.payment.provider.RefundRequest(
                                UUID.randomUUID(),
                                "pi-1",
                                400,
                                "USD",
                                "omp-refund-" + UUID.randomUUID(),
                                "requested"));
        assertThat(result.status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"pending", "requires_action", "succeeded", "failed", "canceled"})
    void lookupReadsExistingRefundWithoutPostingOrSendingIdempotencyHeader(String status) {
        wireMock.stubFor(
                get(urlEqualTo("/v1/refunds/re_1"))
                        .willReturn(
                                okJson(
                                        "{\"id\":\"re_1\",\"status\":\""
                                                + status
                                                + "\",\"amount\":400,\"currency\":\"usd\",\"payment_intent\":\"pi_1\"}")));
        var result =
                provider.lookupRefund(
                        new com.example.omp.payment.provider.RefundRequest(
                                UUID.randomUUID(),
                                "pi_1",
                                400,
                                "USD",
                                "omp-refund-" + UUID.randomUUID(),
                                "requested"),
                        "re_1");
        assertThat(result.status().name())
                .isEqualTo(
                        switch (status) {
                            case "succeeded" -> "SUCCEEDED";
                            case "failed", "canceled" -> "FAILED";
                            default -> "PENDING";
                        });
        assertThat(result.providerReference()).isEqualTo("re_1");
        wireMock.verify(
                getRequestedFor(urlEqualTo("/v1/refunds/re_1"))
                        .withHeader("Authorization", equalTo("Bearer sk_test_123"))
                        .withHeader("Idempotency-Key", absent()));
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"id", "amount", "currency", "payment", "status"})
    void lookupRejectsMismatchedOrUnknownResponse(String changed) {
        var json = new ObjectMapper().createObjectNode();
        json.put("id", changed.equals("id") ? "re_other" : "re_1")
                .put("amount", changed.equals("amount") ? 401 : 400)
                .put("currency", changed.equals("currency") ? "eur" : "usd")
                .put("payment_intent", changed.equals("payment") ? "pi_other" : "pi_1")
                .put("status", changed.equals("status") ? "unknown" : "succeeded");
        wireMock.stubFor(get(urlEqualTo("/v1/refunds/re_1")).willReturn(okJson(json.toString())));
        var result =
                provider.lookupRefund(
                        new com.example.omp.payment.provider.RefundRequest(
                                UUID.randomUUID(), "pi_1", 400, "USD", "key", null),
                        "re_1");
        assertThat(result.status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 404, 409, 429, 500})
    void lookupHttpErrorRemainsPendingWithoutCreatingAReplacementRefund(int status) {
        wireMock.stubFor(
                get(urlEqualTo("/v1/refunds/re_1"))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withBody("{\"error\":{\"message\":\"private\"}}")));
        var result =
                provider.lookupRefund(
                        new com.example.omp.payment.provider.RefundRequest(
                                UUID.randomUUID(), "pi_1", 400, "USD", "key", null),
                        "re_1");
        assertThat(result.status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
        assertThat(result.errorMessage()).doesNotContain("private");
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"../payment_intents/pi_1", "re_1?expand[]=charge", "", "re_1/other"})
    void lookupReferenceCannotAlterTheHttpPath(String reference) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                provider.lookupRefund(
                                        new com.example.omp.payment.provider.RefundRequest(
                                                UUID.randomUUID(), "pi_1", 400, "USD", "key", null),
                                        reference))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"pending", "requires_action", "succeeded", "failed", "canceled"})
    void strictOperatorLookupRequiresMatchingLocalRefundAndPaymentMetadata(String status) {
        UUID local = UUID.randomUUID(), payment = UUID.randomUUID();
        var json = new ObjectMapper().createObjectNode();
        json.put("id", "re_candidate")
                .put("amount", 400)
                .put("currency", "usd")
                .put("payment_intent", "pi_1")
                .put("status", status);
        json.putObject("metadata")
                .put("refund_id", local.toString())
                .put("payment_id", payment.toString());
        wireMock.stubFor(
                get(urlEqualTo("/v1/refunds/re_candidate")).willReturn(okJson(json.toString())));
        var result =
                provider.lookupRefund(
                        new com.example.omp.payment.provider.RefundRequest(
                                payment, "pi_1", 400, "USD", "omp-refund-" + local, "requested"),
                        "re_candidate",
                        local);
        assertThat(result.status().name())
                .isEqualTo(
                        switch (status) {
                            case "succeeded" -> "SUCCEEDED";
                            case "failed", "canceled" -> "FAILED";
                            default -> "PENDING";
                        });
        wireMock.verify(1, getRequestedFor(urlEqualTo("/v1/refunds/re_candidate")));
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"refund", "payment", "missing", "wrong-key", "missing-local-id"})
    void operatorCandidateCannotBeBoundThroughUnrelatedOrMissingMetadata(String changed) {
        UUID local = UUID.randomUUID(), payment = UUID.randomUUID();
        var json = new ObjectMapper().createObjectNode();
        json.put("id", "re_candidate")
                .put("amount", 400)
                .put("currency", "usd")
                .put("payment_intent", "pi_1")
                .put("status", "succeeded");
        if (!changed.equals("missing"))
            json.putObject("metadata")
                    .put(
                            "refund_id",
                            changed.equals("refund")
                                    ? UUID.randomUUID().toString()
                                    : local.toString())
                    .put(
                            "payment_id",
                            changed.equals("payment")
                                    ? UUID.randomUUID().toString()
                                    : payment.toString());
        wireMock.stubFor(
                get(urlEqualTo("/v1/refunds/re_candidate")).willReturn(okJson(json.toString())));
        var result =
                provider.lookupRefund(
                        new com.example.omp.payment.provider.RefundRequest(
                                payment,
                                "pi_1",
                                400,
                                "USD",
                                changed.equals("wrong-key") ? "unbound" : "omp-refund-" + local,
                                null),
                        "re_candidate",
                        changed.equals("missing-local-id") ? null : local);
        assertThat(result.status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
        if (changed.equals("wrong-key") || changed.equals("missing-local-id"))
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void charge_succeeds_when_stripe_returns_succeeded() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/payment_intents"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {"id":"pi_123","status":"succeeded","amount":1000,"currency":"usd"}
                                """)));

        ProviderChargeResult result =
                provider.charge(
                        new ChargeRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                1000L,
                                "USD",
                                "idem-1",
                                "Order test"));

        assertThat(result.status()).isEqualTo(ProviderChargeResult.Status.SUCCEEDED);
        assertThat(result.providerReference()).isEqualTo("pi_123");
    }

    @Test
    void charge_declined_when_stripe_returns_requires_payment_method() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/payment_intents"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {"id":"pi_456","status":"requires_payment_method",
                                 "last_payment_error":{"decline_code":"card_declined",
                                 "message":"Your card was declined."}}
                                """)));

        ProviderChargeResult result =
                provider.charge(
                        new ChargeRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                2000L,
                                "USD",
                                "idem-2",
                                "Order test"));

        assertThat(result.status()).isEqualTo(ProviderChargeResult.Status.DECLINED);
        assertThat(result.declineCode()).isEqualTo("card_declined");
    }

    @Test
    void charge_returns_error_when_stripe_unreachable() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/payment_intents"))
                        .willReturn(
                                aResponse()
                                        .withFault(
                                                com.github.tomakehurst.wiremock.http.Fault
                                                        .CONNECTION_RESET_BY_PEER)));

        ProviderChargeResult result =
                provider.charge(
                        new ChargeRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                3000L,
                                "USD",
                                "idem-3",
                                "Order test"));

        assertThat(result.status()).isEqualTo(ProviderChargeResult.Status.ERROR);
    }

    @Test
    void charge_returns_requires_action_when_3ds_required() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/payment_intents"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {"id":"pi_789","status":"requires_action",
                                 "next_action":{"redirect_to_url":{"url":"https://3ds.example/x"}}}
                                """)));

        ProviderChargeResult result =
                provider.charge(
                        new ChargeRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                4000L,
                                "USD",
                                "idem-4",
                                "Order test"));

        assertThat(result.status()).isEqualTo(ProviderChargeResult.Status.REQUIRES_ACTION);
        assertThat(result.requiresActionUrl()).isEqualTo("https://3ds.example/x");
    }
}

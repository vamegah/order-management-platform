package com.example.omp.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "omp.simulator.webhook-delay-ms=0"})
class SimulatorHttpTest {
    private static final String SECRET = "test-simulator-signing-key";
    private static final LinkedBlockingQueue<Captured> RECEIVED = new LinkedBlockingQueue<>();
    private static final AtomicInteger STATUS = new AtomicInteger(204);
    private static final HttpServer RECEIVER = receiver();

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path fixtureDirectory;
    @Autowired ProviderFixtureLedger ledger;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired WebhookSender sender;
    @Autowired MeterRegistry metrics;

    private record Captured(byte[] body, Map<String, java.util.List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow()
                    .getValue()
                    .getFirst();
        }
    }

    private static HttpServer receiver() {
        try {
            var server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(
                    "/webhook",
                    exchange -> {
                        RECEIVED.add(
                                new Captured(
                                        exchange.getRequestBody().readAllBytes(),
                                        Map.copyOf(exchange.getRequestHeaders())));
                        exchange.sendResponseHeaders(STATUS.get(), -1);
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "omp.simulator.webhook-url",
                () -> "http://localhost:" + RECEIVER.getAddress().getPort() + "/webhook");
        registry.add("omp.simulator.hmac-secret", () -> SECRET);
        registry.add("omp.simulator.data-directory", () -> fixtureDirectory.toString());
    }

    @BeforeEach
    void resetReceiver() {
        RECEIVED.clear();
        STATUS.set(204);
    }

    @AfterAll
    static void closeReceiver() {
        RECEIVER.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"success,true", "success,false", "failure,true", "failure,false"})
    void acceptsAndDeliversCorrelatedSignedWebhook(String outcome, boolean supplied)
            throws Exception {
        var request = new java.util.HashMap<String, Object>();
        request.put("amount", "12.34");
        request.put("currency", "USD");
        if (supplied) {
            request.put("paymentIntentId", "pi_fixture");
            request.put("failureReason", "insufficient_funds");
        }
        var response =
                rest.postForEntity(
                        "/api/simulate/payment/" + outcome,
                        request,
                        PaymentSimulationController.SimulationResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        var acknowledgement = response.getBody();
        assertThat(acknowledgement).isNotNull();
        assertThat(acknowledgement.eventId()).startsWith("evt_");
        assertThat(acknowledgement.paymentIntentId()).startsWith(supplied ? "pi_fixture" : "pi_");
        assertThat(acknowledgement.message()).contains("0ms");
        var delivery = RECEIVED.poll(5, TimeUnit.SECONDS);
        assertThat(delivery).isNotNull();
        var payload = mapper.readValue(delivery.body(), WebhookEventPayload.class);
        assertThat(payload.id()).isEqualTo(acknowledgement.eventId());
        assertThat(payload.paymentIntentId()).isEqualTo(acknowledgement.paymentIntentId());
        assertThat(payload.amount()).isEqualByComparingTo("12.34");
        assertThat(payload.currency()).isEqualTo("USD");
        assertThat(payload.createdAt().getEpochSecond()).isEqualTo(payload.created());
        assertThat(payload.type())
                .isEqualTo(
                        "payment_intent."
                                + (outcome.equals("success") ? "succeeded" : "payment_failed"));
        assertThat(payload.status()).isEqualTo(outcome.equals("success") ? "succeeded" : "failed");
        assertThat(payload.failureReason())
                .isEqualTo(
                        outcome.equals("success")
                                ? null
                                : supplied ? "insufficient_funds" : "card_declined");
        assertThat(delivery.header("X-Webhook-Id")).isEqualTo(payload.id());
        assertThat(delivery.header("X-Webhook-Event")).isEqualTo(payload.type());
        assertThat(delivery.header("Content-Type")).startsWith("application/json");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((delivery.header("X-Timestamp") + ".").getBytes(StandardCharsets.UTF_8));
        assertThat(delivery.header("X-Signature-256"))
                .isEqualTo("sha256=" + HexFormat.of().formatHex(mac.doFinal(delivery.body())));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"amount\":0,\"currency\":\"USD\"}",
                "{\"amount\":-1,\"currency\":\"USD\"}",
                "{\"currency\":\"USD\"}",
                "{\"amount\":1,\"currency\":\" \"}"
            })
    void rejectsInvalidRequestBeforeScheduling(String body) throws Exception {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response =
                rest.postForEntity(
                        "/api/simulate/payment/success",
                        new HttpEntity<>(body, headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(RECEIVED.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void deliveryFailureIsMeasuredWithoutPretendingSuccess() {
        STATUS.set(503);
        double before =
                metrics.counter("simulator.webhook.send.errors", "reason", "delivery").count();
        var payload =
                WebhookEventPayload.succeeded(
                        "evt_failure", "pi_fixture", java.math.BigDecimal.ONE, "USD");
        assertThat(sender.send(payload)).isFalse();
        assertThat(metrics.counter("simulator.webhook.send.errors", "reason", "delivery").count())
                .isEqualTo(before + 1);
    }

    private org.springframework.http.HttpHeaders fixtureHeaders(boolean form) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("sk_test_omp_simulator");
        headers.setContentType(
                form ? MediaType.APPLICATION_FORM_URLENCODED : MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void providerHttpApiPreservesIdentityAndCompletesPendingRefunds() throws Exception {
        ledger.configure(mapper.readTree("{\"dropCallbacks\":true,\"delayMs\":0}"));
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.set("amount", "1500");
        form.set("currency", "usd");
        form.set("payment_method", "pm_success");
        form.set("metadata[payment_id]", java.util.UUID.randomUUID().toString());
        form.set("metadata[order_id]", java.util.UUID.randomUUID().toString());
        form.set("metadata[customer_id]", java.util.UUID.randomUUID().toString());
        var headers = fixtureHeaders(true);
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());
        var created =
                rest.postForEntity(
                        "/v1/payment_intents",
                        new HttpEntity<>(form, headers),
                        com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String id = created.getBody().path("id").asText();
        var same =
                rest.postForEntity(
                        "/v1/payment_intents",
                        new HttpEntity<>(form, headers),
                        com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(same.getBody().path("id").asText()).isEqualTo(id);
        var read =
                rest.exchange(
                        "/v1/payment_intents/" + id,
                        org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(fixtureHeaders(false)),
                        com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(read.getBody().path("status").asText()).isEqualTo("succeeded");
        form.clear();
        form.set("payment_intent", id);
        form.set("amount", "500");
        form.set("metadata[payment_id]", java.util.UUID.randomUUID().toString());
        form.set("metadata[refund_id]", java.util.UUID.randomUUID().toString());
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());
        var refund =
                rest.postForEntity(
                        "/v1/refunds",
                        new HttpEntity<>(form, headers),
                        com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(refund.getBody().path("status").asText()).isEqualTo("pending");
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            var result =
                                    rest.exchange(
                                            "/v1/refunds/" + refund.getBody().path("id").asText(),
                                            org.springframework.http.HttpMethod.GET,
                                            new HttpEntity<>(fixtureHeaders(false)),
                                            com.fasterxml.jackson.databind.JsonNode.class);
                            assertThat(result.getBody().path("status").asText())
                                    .isEqualTo("succeeded");
                        });
        assertThat(
                        rest.exchange(
                                        "/__test/stats",
                                        org.springframework.http.HttpMethod.GET,
                                        new HttpEntity<>(fixtureHeaders(false)),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    void fixtureControlsRejectUnauthorizedAndOversizedJson() {
        assertThat(rest.getForEntity("/__test/stats", String.class).getStatusCode().value())
                .isEqualTo(401);
        var wrong = fixtureHeaders(false);
        wrong.setBearerAuth("wrong");
        assertThat(
                        rest.exchange(
                                        "/__test/stats",
                                        org.springframework.http.HttpMethod.GET,
                                        new HttpEntity<>(wrong),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        var valid = fixtureHeaders(false);
        assertThat(
                        rest.exchange(
                                        "/__test/controls",
                                        org.springframework.http.HttpMethod.PUT,
                                        new HttpEntity<>(
                                                "{\"padding\":\"" + "x".repeat(16385) + "\"}",
                                                valid),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(413);
        assertThat(
                        rest.exchange(
                                        "/__test/controls",
                                        org.springframework.http.HttpMethod.PUT,
                                        new HttpEntity<>(
                                                Map.of("dropCallbacks", true, "delayMs", 0), valid),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    void providerActionAndCancellationUseTheAuthenticatedHttpContract() throws Exception {
        ledger.configure(mapper.readTree("{\"dropCallbacks\":true,\"delayMs\":0}"));
        String action =
                ledger.charge(
                                new java.util.HashMap<>(
                                        Map.of(
                                                "amount",
                                                "1000",
                                                "currency",
                                                "usd",
                                                "payment_method",
                                                "pm_action",
                                                "metadata[payment_id]",
                                                java.util.UUID.randomUUID().toString(),
                                                "metadata[order_id]",
                                                java.util.UUID.randomUUID().toString(),
                                                "metadata[customer_id]",
                                                java.util.UUID.randomUUID().toString())),
                                "http-action")
                        .path("id")
                        .asText();
        var completed =
                rest.postForEntity(
                        "/__test/payment_intents/" + action + "/complete",
                        new HttpEntity<>(Map.of("success", false), fixtureHeaders(false)),
                        com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(completed.getBody().path("status").asText())
                .isEqualTo("requires_payment_method");
        var cancelled =
                rest.postForEntity(
                        "/v1/payment_intents/" + action + "/cancel",
                        new HttpEntity<>(fixtureHeaders(false)),
                        com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(cancelled.getBody().path("status").asText()).isEqualTo("canceled");
    }
}

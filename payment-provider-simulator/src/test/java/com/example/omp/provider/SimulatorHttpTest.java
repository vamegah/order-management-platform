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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "omp.simulator.webhook-delay-ms=0"})
class SimulatorHttpTest {
    private static final String SECRET = "test-simulator-signing-key";
    private static final LinkedBlockingQueue<Captured> RECEIVED = new LinkedBlockingQueue<>();
    private static final AtomicInteger STATUS = new AtomicInteger(204);
    private static final HttpServer RECEIVER = receiver();

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
}

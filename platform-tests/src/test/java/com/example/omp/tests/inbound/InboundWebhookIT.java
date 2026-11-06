package com.example.omp.tests.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.payment.PaymentServiceApplication;
import com.example.omp.testsupport.ServiceTestProperties;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Real HTTP contract for the provider-specific payment webhook receiver. */
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=file:payment-service/src/main/resources/application.yml,classpath:application-test.yml",
            "payment.provider=stripe",
            "payment.stripe.api-key=sk_test_fixture",
            "payment.stripe.webhook-secret=test-signing-secret",
            "grpc.server.security.enabled=false",
            "grpc.server.port=0",
            "management.server.port=0"
        })
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class InboundWebhookIT {
    private static final String SECRET = "test-signing-secret";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ServiceTestProperties.register(registry, "payment-service", "payments");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE payments.provider_event");
    }

    private String event(String id) {
        return "{\"id\":\""
                + id
                + "\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_unknown\"}}}";
    }

    private String sign(long timestamp, String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t="
                + timestamp
                + ",v1="
                + HexFormat.of()
                        .formatHex(
                                mac.doFinal(
                                        (timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/v1/payments/webhooks/stripe"))
                        .header("Content-Type", "application/json")
                        .header("Stripe-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM payments.provider_event", Integer.class);
    }

    @Test
    void validSignaturePersistsUnknownPaymentEventForAudit() throws Exception {
        String body = event("evt_valid");
        assertThat(post(body, sign(System.currentTimeMillis() / 1000, body)).statusCode())
                .isEqualTo(200);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void invalidSignatureDoesNotPersist() throws Exception {
        assertThat(
                        post(
                                        event("evt_invalid"),
                                        "t=" + System.currentTimeMillis() / 1000 + ",v1=deadbeef")
                                .statusCode())
                .isEqualTo(401);
        assertThat(count()).isZero();
    }

    @Test
    void staleSignedEventIsRejected() throws Exception {
        String body = event("evt_stale");
        assertThat(post(body, sign(System.currentTimeMillis() / 1000 - 3600, body)).statusCode())
                .isEqualTo(401);
        assertThat(count()).isZero();
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutAnotherRow() throws Exception {
        String body = event("evt_duplicate"),
                signature = sign(System.currentTimeMillis() / 1000, body);
        assertThat(post(body, signature).statusCode()).isEqualTo(200);
        var duplicate = post(body, signature);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(duplicate.body()).contains("duplicate");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void reusedEventIdCannotReplaceTheOriginalPayload() throws Exception {
        String first = event("evt_immutable"), second = first.replace("pi_unknown", "pi_altered");
        post(first, sign(System.currentTimeMillis() / 1000, first));
        assertThat(post(second, sign(System.currentTimeMillis() / 1000, second)).body())
                .contains("duplicate");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM payments.provider_event WHERE provider_event_id='evt_immutable'",
                                String.class))
                .doesNotContain("pi_altered");
    }
}

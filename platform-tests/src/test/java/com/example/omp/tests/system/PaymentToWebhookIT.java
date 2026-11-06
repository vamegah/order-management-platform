package com.example.omp.tests.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.NotificationServiceApplication;
import com.example.omp.notification.webhook.matcher.WebhookEventTypes;
import com.example.omp.payment.PaymentServiceApplication;
import com.example.omp.testsupport.KafkaTestConfig;
import com.example.omp.testsupport.KafkaTestHelper;
import com.example.omp.testsupport.MockSubscriber;
import com.example.omp.testsupport.ServiceTestProperties;
import com.example.omp.testsupport.SharedContainers;
import com.example.omp.testsupport.TestJwtIssuer;
import com.example.omp.testsupport.TestRetryScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP and Kafka between independent payment and notification application contexts. */
class PaymentToWebhookIT {
    private static ConfigurableApplicationContext payments;
    private static ConfigurableApplicationContext notifications;
    private static TestJwtIssuer issuer;
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TestRestTemplate rest = new TestRestTemplate();
    private JdbcTemplate paymentJdbc;
    private JdbcTemplate notificationJdbc;
    private MockSubscriber subscriber;
    private String token;
    private String subscriptionId;

    @BeforeAll
    static void startServices() throws Exception {
        try {
            issuer = new TestJwtIssuer();
            notifications =
                    start(
                            "notification-service",
                            "notifications",
                            NotificationServiceApplication.class,
                            KafkaTestConfig.class,
                            TestRetryScheduler.class);
            payments = start("payment-service", "payments", PaymentServiceApplication.class);
        } catch (Exception | Error e) {
            stopServices();
            throw e;
        }
    }

    private static ConfigurableApplicationContext start(
            String service, String schema, Class<?>... sources) {
        var properties = ServiceTestProperties.forService(service, schema);
        properties.put("spring.profiles.active", "test,test-fast-retry");
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer.issuer());
        properties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", issuer.jwksUri());
        properties.put("payment.provider", "noop");
        String[] args =
                properties.entrySet().stream()
                        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                        .toArray(String[]::new);
        return new SpringApplicationBuilder(sources).profiles("test", "test-fast-retry").run(args);
    }

    @AfterAll
    static void stopServices() {
        if (payments != null) payments.close();
        if (notifications != null) notifications.close();
        if (issuer != null) issuer.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        paymentJdbc = payments.getBean(JdbcTemplate.class);
        notificationJdbc = notifications.getBean(JdbcTemplate.class);
        paymentJdbc.execute(
                "TRUNCATE payments.outbox_event, payments.provider_event, payments.payment CASCADE");
        notificationJdbc.execute("TRUNCATE notifications.webhook_subscription CASCADE");
        subscriber = new MockSubscriber();
        token = issuer.token(CUSTOMER_ID);
    }

    @AfterEach
    void tearDown() {
        try {
            KafkaTestHelper.awaitWebhookConsumersCaughtUp(
                    SharedContainers.kafkaBootstrap(), "platform-tests");
        } finally {
            if (subscriber != null) subscriber.close();
        }
    }

    @Test
    void payment_confirmed_flows_to_subscriber() throws Exception {
        subscribe("payment.confirmed");
        var response = charge(1501, "sys-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("result", "confirmed");
        String id = (String) response.getBody().get("paymentId");
        awaitDelivery("payment.confirmed", id, 1);
        assertThat(
                        paymentJdbc.queryForObject(
                                "SELECT status FROM payments.payment WHERE id=?::uuid",
                                String.class,
                                id))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void declined_payment_emits_payment_failed_and_delivers() throws Exception {
        subscribe("payment.failed");
        var response = charge(1000, "decline-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody())
                .containsEntry("result", "declined")
                .containsEntry("failureCode", "card_declined");
        String id = (String) response.getBody().get("paymentId");
        awaitDelivery("payment.failed", id, 1);
        JsonNode event = MAPPER.readTree(outboxEvent("payment.failed", id));
        assertThat(event.path("data").path("failureCode").asText()).isEqualTo("card_declined");
    }

    @Test
    void refund_flows_to_subscriber() throws Exception {
        subscribe("payment.confirmed", "payment.refunded");
        var response = charge(1501, "refund-charge-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = (String) response.getBody().get("paymentId");
        awaitDelivery("payment.confirmed", id, 1);
        var refund =
                rest.exchange(
                        paymentUrl("/api/v1/payments/" + id + "/refunds"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "amountMinor",
                                        1501,
                                        "reason",
                                        "customer_request",
                                        "idempotencyKey",
                                        "refund-" + UUID.randomUUID()),
                                headers()),
                        Map.class);
        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refund.getBody()).containsEntry("status", "COMPLETED");
        awaitDelivery("payment.refunded", id, 2);
        assertThat(
                        paymentJdbc.queryForObject(
                                "SELECT status FROM payments.payment WHERE id=?::uuid",
                                String.class,
                                id))
                .isEqualTo("REFUNDED");
    }

    @Test
    void idempotent_charge_does_not_duplicate_webhook() throws Exception {
        subscribe("payment.confirmed");
        var body = chargeBody(1501, "idem-" + UUID.randomUUID());
        var first = postCharge(body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitDelivery("payment.confirmed", (String) first.getBody().get("paymentId"), 1);
        var second = postCharge(body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        KafkaTestHelper.awaitWebhookConsumersCaughtUp(
                SharedContainers.kafkaBootstrap(), "platform-tests");
        assertThat(subscriber.requestCount.get()).isEqualTo(1);
        assertThat(
                        paymentJdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE event_type='payment.confirmed'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void both_service_boundaries_require_a_verified_bearer_token() {
        assertThat(
                        rest.postForEntity(
                                        paymentUrl("/api/v1/payments"),
                                        chargeBody(1501, UUID.randomUUID().toString()),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.getForEntity(
                                        notificationUrl("/api/v1/webhook-subscriptions"),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void subscribe(String... eventTypes) {
        var response =
                rest.exchange(
                        notificationUrl("/api/v1/webhook-subscriptions"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "name",
                                        "platform-system",
                                        "targetUrl",
                                        subscriber.url(),
                                        "eventTypes",
                                        eventTypes),
                                headers()),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        subscriptionId = (String) response.getBody().get("id");
        assertThat(subscriptionId).isNotBlank();
    }

    private ResponseEntity<Map> charge(long amount, String key) {
        return postCharge(chargeBody(amount, key));
    }

    private Map<String, Object> chargeBody(long amount, String key) {
        return Map.of(
                "orderId",
                UUID.randomUUID().toString(),
                "amountMinor",
                amount,
                "currency",
                "USD",
                "idempotencyKey",
                key);
    }

    private ResponseEntity<Map> postCharge(Map<String, Object> body) {
        return rest.exchange(
                paymentUrl("/api/v1/payments"),
                HttpMethod.POST,
                new HttpEntity<>(body, headers()),
                Map.class);
    }

    private HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private String outboxEvent(String type, String paymentId) {
        var types = WebhookEventTypes.matchingTypes(type);
        return paymentJdbc.queryForObject(
                "SELECT payload FROM payments.outbox_event WHERE event_type IN (?, ?) AND aggregate_id=?",
                String.class,
                type,
                types.get(types.size() - 1),
                paymentId);
    }

    private void awaitDelivery(String type, String paymentId, int count) throws Exception {
        await().atMost(Duration.ofSeconds(40))
                .untilAsserted(
                        () -> {
                            assertThat(subscriber.requestCount.get()).isEqualTo(count);
                            assertThat(
                                            notificationJdbc.queryForObject(
                                                    "SELECT count(*) FROM notifications.webhook_delivery_attempt WHERE subscription_id=?::uuid AND status='SUCCESS'",
                                                    Integer.class,
                                                    subscriptionId))
                                    .isEqualTo(count);
                            var types = WebhookEventTypes.matchingTypes(type);
                            assertThat(
                                            paymentJdbc.queryForObject(
                                                    "SELECT status FROM payments.outbox_event WHERE event_type IN (?, ?) AND aggregate_id=?",
                                                    String.class,
                                                    type,
                                                    types.get(types.size() - 1),
                                                    paymentId))
                                    .isEqualTo("PUBLISHED");
                        });
        JsonNode event = MAPPER.readTree(outboxEvent(type, paymentId));
        String eventId = event.path("eventId").asText();
        JsonNode data = event.has("data") ? event.path("data") : event;
        assertThat(data.path("paymentId").asText()).isEqualTo(paymentId);
        boolean matched = false;
        for (String body : subscriber.bodies) {
            JsonNode webhook = MAPPER.readTree(body);
            if (!webhook.path("eventType").asText().equals(type)) continue;
            assertThat(webhook.path("eventId").asText()).isEqualTo(eventId);
            // Current outbound contract carries an event reference. Full payload enrichment
            // remains a separate backlog item; the original payload is verified above.
            assertThat(webhook.path("data").path("eventId").asText()).isEqualTo(eventId);
            matched = true;
        }
        assertThat(matched).as("Delivered event %s", type).isTrue();
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        return "http://localhost:"
                + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private String paymentUrl(String path) {
        return baseUrl(payments) + path;
    }

    private String notificationUrl(String path) {
        return baseUrl(notifications) + path;
    }
}

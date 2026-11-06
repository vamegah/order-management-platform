package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.omp.notification.webhook.SubscriptionOwnerType;
import com.example.omp.notification.webhook.WebhookSubscription;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Wired test endpoint (Batch 9.4 file 42).
 *
 * <p>POST /api/v1/webhook-subscriptions/{id}/test publishes a synthetic webhook.test event through
 * the real delivery pipeline.
 *
 * <p>This test exercises the controller + delivery path end to end. JWT auth is faked via a mocked
 * JwtDecoder that returns a JWT for the subscription's owner.
 */
class OutboundWebhookTestEndpointIT extends OutboundWebhookBase {

    @Autowired TestRestTemplate rest;

    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void test_endpoint_publishes_a_synthetic_delivery() throws Exception {
        // Create a subscription and its owner.
        UUID ownerId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(SubscriptionOwnerType.CUSTOMER);
        sub.setOwnerId(ownerId);
        sub.setName("test-endpoint");
        sub.setTargetUrl(subscriber.url());
        sub.setSecret("0123456789abcdef0123456789abcdef0123456789");
        sub.setEventTypes(List.of("payment.confirmed", "webhook.test"));
        sub.setEnabled(true);
        subscriptions.save(sub);

        // Stub the JWT decoder to return a token for this owner.
        Jwt jwt =
                Jwt.withTokenValue("test-token")
                        .header("alg", "none")
                        .claim("sub", ownerId.toString())
                        .claim("customer_id", ownerId.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);

        subscriber.enqueueResponse(200);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/webhook-subscriptions/" + sub.getId() + "/test",
                        HttpMethod.POST,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsKey("deliveryId");
        assertThat(response.getBody().get("status")).isEqualTo("queued");

        // The synthetic event should reach the subscriber.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isEqualTo(1));

        String body = subscriber.bodies.get(0);
        assertThat(body).contains("webhook.test");
        assertThat(body).contains("\"message\":\"This is a test event");
    }
}

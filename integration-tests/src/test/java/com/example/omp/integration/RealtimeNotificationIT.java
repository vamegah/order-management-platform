package com.example.omp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

/**
 * End-to-end test: place an order via REST, verify WebSocket delivery.
 *
 * <p>Requires the full Docker stack:
 *
 * <pre>
 *   docker compose up -d
 *   ./mvnw verify -Pe2e -pl integration-tests
 * </pre>
 *
 * <p>Exercises the full real-time pipeline:
 *
 * <ol>
 *   <li>Order state change in order-service.
 *   <li>Event published to Kafka.
 *   <li>notification-service consumes from Kafka.
 *   <li>Publishes to Redis.
 *   <li>Redis subscriber dispatches to WebSocket.
 *   <li>Client receives the event.
 * </ol>
 */
@Tag("e2e")
class RealtimeNotificationIT {

    private static final String NOTIFICATION_WS_URL =
            System.getenv().getOrDefault("NOTIFICATION_WS_URL", "ws://notification.localhost/ws");
    private static final String METRICS_URL =
            System.getenv()
                    .getOrDefault(
                            "NOTIFICATION_METRICS_URL",
                            "http://localhost:18085/actuator/prometheus");

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    private double subscriptionCount() {
        String body =
                OrderJourney.authenticated(AuthSupport.adminToken())
                        .get(METRICS_URL)
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();
        return body.lines()
                .filter(
                        line ->
                                line.startsWith("websocket_subscriptions_active ")
                                        || line.startsWith("websocket_subscriptions_active{"))
                .mapToDouble(line -> Double.parseDouble(line.split(" ")[1]))
                .sum();
    }

    @Test
    void placeOrderTriggersWebSocketEvent() throws Exception {
        var journey = OrderJourney.create();
        assertThat(subscriptionCount()).as("Run against an isolated e2e stack").isZero();
        // Container health can precede Traefik's discovery of a replacement.
        String infoUrl =
                NOTIFICATION_WS_URL.replaceFirst("^ws", "http").replaceAll("/+$", "") + "/info";
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                OrderJourney.authenticated(AuthSupport.aliceToken())
                                        .get(infoUrl)
                                        .then()
                                        .statusCode(200)
                                        .body("websocket", org.hamcrest.Matchers.is(true)));
        // Connect a WebSocket client and subscribe to this case's new order.
        WebSocketStompClient stompClient =
                new WebSocketStompClient(
                        new SockJsClient(
                                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + AuthSupport.aliceToken());

        StompSession session = null;
        try {
            session =
                    stompClient
                            .connectAsync(
                                    NOTIFICATION_WS_URL,
                                    new WebSocketHttpHeaders(),
                                    connectHeaders,
                                    new StompSessionHandlerAdapter() {})
                            .get(10, TimeUnit.SECONDS);

            CompletableFuture<Map<?, ?>> received = new CompletableFuture<>();
            var subscription =
                    session.subscribe(
                            "/topic/orders/" + journey.id(),
                            new StompFrameHandler() {
                                @Override
                                public Type getPayloadType(StompHeaders headers) {
                                    return Map.class;
                                }

                                @Override
                                public void handleFrame(StompHeaders headers, Object payload) {
                                    if (payload instanceof Map<?, ?> event
                                            && "ORDER_PLACED".equals(event.get("eventType"))
                                            && event.get("data") instanceof Map<?, ?> data
                                            && journey.id().equals(data.get("orderId"))) {
                                        received.complete(event);
                                    }
                                }
                            });

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(subscriptionCount()).isEqualTo(1));

            // 2. Place the order via REST.
            journey.transition("place", false).then().statusCode(200);

            // 3. Wait for the WebSocket event.
            Map<?, ?> event = received.get(30, TimeUnit.SECONDS);
            assertThat(event.get("eventType")).isEqualTo("ORDER_PLACED");
            subscription.unsubscribe();
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(subscriptionCount()).isZero());

        } finally {
            if (session != null && session.isConnected()) session.disconnect();
            stompClient.stop();
            journey.transition("cancel", false).then().statusCode(200);
        }
    }
}

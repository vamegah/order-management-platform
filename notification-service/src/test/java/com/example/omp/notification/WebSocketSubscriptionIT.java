package com.example.omp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.example.omp.notification.clients.OrderClient;
import com.example.omp.notification.clients.dto.OrderDto;
import com.example.omp.notification.dispatcher.EventDispatcher;
import com.example.omp.notification.subscription.SubscriptionRegistry;
import com.example.omp.notification.subscription.TopicType;
import com.example.omp.notification.support.TestTokens;
import com.example.omp.shared.events.BaseEvent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

/** Integration test: connect a STOMP client, subscribe, dispatch an event, verify delivery. */
class WebSocketSubscriptionIT extends AbstractNotificationTest {

    private static final String ORDER_ID = "00000000-0000-0000-0000-000000000301";

    @LocalServerPort int port;

    @Autowired EventDispatcher dispatcher;
    @Autowired SubscriptionRegistry registry;
    @MockBean OrderClient orders;

    WebSocketStompClient stompClient;
    StompSession session;

    @BeforeEach
    void setup() throws Exception {
        when(orders.getOrder(UUID.fromString(ORDER_ID)))
                .thenReturn(
                        new OrderDto(
                                UUID.fromString(ORDER_ID),
                                "ORD-TEST",
                                TestTokens.ALICE_CUSTOMER_ID,
                                "PLACED"));
        stompClient =
                new WebSocketStompClient(
                        new SockJsClient(
                                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + TestTokens.alice());

        session =
                stompClient
                        .connectAsync(
                                "ws://localhost:" + port + "/ws",
                                new WebSocketHttpHeaders(),
                                headers,
                                new StompSessionHandlerAdapter() {})
                        .get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void cleanup() {
        if (session != null && session.isConnected()) session.disconnect();
        if (stompClient != null) stompClient.stop();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(registry.totalSubscriptions()).isZero());
    }

    @Test
    void subscribedClientReceivesDispatchedEvent() throws Exception {
        CompletableFuture<Map<?, ?>> received = new CompletableFuture<>();

        var subscription =
                session.subscribe(
                        "/topic/orders/" + ORDER_ID,
                        new StompFrameHandler() {
                            @Override
                            public Type getPayloadType(StompHeaders headers) {
                                return Map.class;
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                received.complete((Map<?, ?>) payload);
                            }
                        });

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(registry.find(TopicType.ORDERS, ORDER_ID)).hasSize(1));

        // Wait for both the application registry and the broker's subscription to be ready.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            dispatcher.dispatch(fakeOrderEvent(ORDER_ID));
                            assertThat(received).isCompleted();
                        });

        Map<?, ?> envelope = received.get(10, TimeUnit.SECONDS);
        assertThat(envelope.get("eventType")).isEqualTo("ORDER_PLACED");
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        assertThat(data.get("eventId")).isEqualTo("evt-1");
        assertThat(data.get("orderId")).isEqualTo(ORDER_ID);
        subscription.unsubscribe();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(registry.totalSubscriptions()).isZero());
    }

    private BaseEvent fakeOrderEvent(String orderId) {
        return new com.example.omp.shared.events.OrderEvents.OrderPlaced(
                "evt-1",
                com.example.omp.shared.events.OrderEventType.ORDER_PLACED,
                java.time.Instant.now(),
                1,
                orderId,
                "ORD-TEST",
                "00000000-0000-0000-0000-000000000101",
                "PLACED",
                "alice");
    }
}

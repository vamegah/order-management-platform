package com.example.omp.notification.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.example.omp.notification.clients.*;
import com.example.omp.notification.config.*;
import com.example.omp.notification.dispatcher.*;
import com.example.omp.notification.subscription.*;
import com.example.omp.shared.events.OrderEvents;
import java.lang.reflect.Type;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.data.redis.*;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.*;

/** Runs the production broker/interceptors on a real socket, without databases or Docker. */
@SpringBootTest(
        classes = WebSocketBrokerTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=websocket-test", "omp.outbox.enabled=false"})
class WebSocketBrokerTest {
    static final UUID CUSTOMER = UUID.randomUUID();

    @Configuration
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @EnableConfigurationProperties(NotificationProperties.class)
    @Import({
        WebSocketConfig.class,
        SecurityConfig.class,
        StompAuthChannelInterceptor.class,
        SubscriptionInterceptor.class,
        SubscriptionRegistry.class,
        OwnershipVerifier.class,
        EventDispatcherImpl.class,
        DestinationResolver.class,
        SessionEventListener.class
    })
    static class Config {
        @Bean
        CustomerClient customers() {
            return mock(CustomerClient.class);
        }

        @Bean
        OrderClient orders() {
            return mock(OrderClient.class);
        }

        @Bean
        PaymentClient payments() {
            return mock(PaymentClient.class);
        }

        @Bean
        JwtDecoder decoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("stable-subject")
                            .claim("preferred_username", "display-name")
                            .claim("customer_id", CUSTOMER.toString())
                            .expiresAt(Instant.now().plusSeconds(300))
                            .build();
        }
    }

    @LocalServerPort int port;
    @Autowired SubscriptionRegistry registry;
    @Autowired EventDispatcher dispatcher;
    WebSocketStompClient client;
    StompSession session;

    @AfterEach
    void cleanup() {
        if (session != null && session.isConnected()) session.disconnect();
        if (client != null) client.stop();
    }

    @Test
    void authenticatedPrivateQueueReceivesEventAndExplicitUnsubscribeCleansRegistry()
            throws Exception {
        client =
                new WebSocketStompClient(
                        new SockJsClient(
                                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(
                new org.springframework.messaging.converter.MappingJackson2MessageConverter());
        var headers = new StompHeaders();
        headers.add("Authorization", "Bearer test-token");
        session =
                client.connectAsync(
                                "ws://localhost:" + port + "/ws",
                                new org.springframework.web.socket.WebSocketHttpHeaders(),
                                headers,
                                new StompSessionHandlerAdapter() {})
                        .get(10, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isTrue();
        var received = new CompletableFuture<String>();
        var subscription =
                session.subscribe(
                        "/user/queue/notifications",
                        new StompFrameHandler() {
                            @Override
                            public Type getPayloadType(StompHeaders headers) {
                                return Map.class;
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                received.complete(payload.toString());
                            }
                        });
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.totalSubscriptions()).isEqualTo(1));
        var event =
                new OrderEvents.OrderPlaced(
                        "private-event",
                        "ORDER_PLACED",
                        Instant.now(),
                        1,
                        UUID.randomUUID().toString(),
                        "ORD",
                        CUSTOMER.toString(),
                        "PLACED",
                        "actor");
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            dispatcher.dispatch(event);
                            assertThat(received).isCompleted();
                        });
        assertThat(received.get()).contains("private-event");
        subscription.unsubscribe();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.totalSubscriptions()).isZero());
    }
}

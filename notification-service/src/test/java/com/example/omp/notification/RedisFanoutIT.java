package com.example.omp.notification;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.omp.notification.dispatcher.EventDispatcher;
import com.example.omp.notification.redis.RedisChannels;
import com.example.omp.notification.redis.RedisEventPublisher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Integration test: publish to Redis, verify the subscriber dispatches to the {@link
 * EventDispatcher}.
 */
class RedisFanoutIT extends AbstractNotificationTest {

    @Autowired RedisEventPublisher publisher;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisMessageListenerContainer listener;

    @MockitoSpyBean EventDispatcher dispatcher;

    @Test
    void redisMessageTriggersDispatch() {
        String eventId = UUID.randomUUID().toString();
        String eventJson =
                """
            {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2025-01-15T14:23:45.123Z",
             "schemaVersion":1,"orderId":"00000000-0000-0000-0000-000000000301",
             "orderNumber":"ORD-1","customerId":"00000000-0000-0000-0000-000000000101",
             "status":"DRAFT","customerEmail":"a@b.c","currency":"USD","subtotal":100,"lineCount":1}
            """
                        .formatted(eventId);

        // Pub/sub does not retain a message sent before its subscription is ready.
        // This Redis instance belongs to this fixture; wait for server acknowledgment
        // of the listener's three channels, then publish the event exactly once.
        RedisCallback<Boolean> subscriptionsReady =
                connection ->
                        connection.serverCommands().getClientList().stream()
                                .anyMatch(
                                        client ->
                                                client.getChannelSubscribtions() != null
                                                        && client.getChannelSubscribtions()
                                                                >= RedisChannels.ALL.length);
        await().alias("fixture Redis subscriber ready")
                .atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                listener.isListening()
                                        && Boolean.TRUE.equals(redis.execute(subscriptionsReady)));
        publisher.publishToOrdersChannel(eventJson);

        // The dispatcher should be invoked within a few seconds.
        verify(dispatcher, timeout(10_000))
                .dispatch(argThat(event -> eventId.equals(event.eventId())));
    }
}

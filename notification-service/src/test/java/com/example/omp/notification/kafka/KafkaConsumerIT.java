package com.example.omp.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.notification.AbstractNotificationTest;
import com.example.omp.notification.redis.RedisChannels;
import com.example.omp.shared.events.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.core.KafkaTemplate;

/** Integration test: publish a Kafka message and verify it's forwarded to the Redis channel. */
class KafkaConsumerIT extends AbstractNotificationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @Autowired RedisMessageListenerContainer redisContainer;

    @Test
    void orderEventIsForwardedToRedis() throws Exception {
        CompletableFuture<String> redisMessage = new CompletableFuture<>();

        String eventId = UUID.randomUUID().toString();
        MessageListener listener =
                (message, pattern) -> {
                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                    if (body.contains(eventId)) redisMessage.complete(body);
                };
        redisContainer.addMessageListener(listener, new ChannelTopic(RedisChannels.ORDERS));

        // Publish to Kafka.
        String eventJson =
                """
            {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2025-01-15T14:23:45.123Z",
             "schemaVersion":1,"orderId":"00000000-0000-0000-0000-000000000301",
             "orderNumber":"ORD-1","customerId":"00000000-0000-0000-0000-000000000101",
             "status":"DRAFT","customerEmail":"a@b.c","currency":"USD","subtotal":100,"lineCount":1}
            """
                        .formatted(eventId);

        try {
            kafkaTemplate
                    .send(KafkaTopics.ORDERS_EVENTS_ORDER, "order-key", eventJson)
                    .get(10, TimeUnit.SECONDS);
            String received = redisMessage.get(30, TimeUnit.SECONDS);
            assertThat(received).contains(eventId, "ORDER_CREATED");
        } finally {
            redisContainer.removeMessageListener(listener);
        }
    }
}

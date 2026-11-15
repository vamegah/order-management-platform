package com.example.omp.notification.webhook.matcher;

import com.example.omp.notification.durable.DispatchQueue;
import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** All initial, retry and operator-replay handoffs participate in the database transaction. */
@Component
public class WebhookDeliveryPublisher {
    private final DispatchQueue queue;
    private final ObjectMapper mapper;

    public WebhookDeliveryPublisher(DispatchQueue queue, ObjectMapper mapper) {
        this.queue = queue;
        this.mapper = mapper;
    }

    public void enqueue(DeliveryMessage message) {
        publish(message);
    }

    public void publish(DeliveryMessage message) {
        publish(WebhookKafkaTopics.DELIVERIES, message);
    }

    @Transactional
    public void publish(String topic, DeliveryMessage message) {
        message.validate();
        try {
            Instant due =
                    message.scheduledAtMillis() == null
                            ? Instant.now()
                            : Instant.ofEpochMilli(message.scheduledAtMillis());
            queue.enqueue(
                    UUID.randomUUID().toString(),
                    "KAFKA",
                    topic,
                    message.deliveryId().toString(),
                    mapper.writeValueAsString(message),
                    due);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid delivery handoff", e);
        }
    }
}

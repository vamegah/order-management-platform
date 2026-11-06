package com.example.omp.notification.webhook.matcher;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public WebhookDeliveryPublisher(
            KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(DeliveryMessage message) {
        publish(WebhookKafkaTopics.DELIVERIES, message);
    }

    public void publish(String topic, DeliveryMessage message) {
        try {
            String key = message.deliveryId().toString();
            String payload = objectMapper.writeValueAsString(message);
            // The consuming listener must not commit its input before Kafka acknowledges fan-out.
            kafkaTemplate.send(topic, key, payload).get(10, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize DeliveryMessage", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing webhook delivery", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge webhook delivery", e);
        }
    }
}

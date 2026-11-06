package com.example.omp.notification.webhook.dlq;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the DLQ topic.
 *
 * <p>The delivery worker already persists DLQ entries inline (so replay works even if this consumer
 * is down). This consumer exists as a safety net: if a producer publishes directly to the DLQ
 * topic, or if inline persistence failed, the consumer will pick up the message and ensure a row
 * exists.
 *
 * <p>Idempotency: DlqService.persist is a no-op if an entry already exists for the delivery id.
 */
@Component
public class DlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumer.class);

    private final DlqService dlqService;
    private final WebhookDlqRepository repository;
    private final ObjectMapper objectMapper;

    public DlqConsumer(
            DlqService dlqService, WebhookDlqRepository repository, ObjectMapper objectMapper) {
        this.dlqService = dlqService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = WebhookKafkaTopics.DLQ,
            groupId = "${spring.kafka.consumer.group-id}-webhook-dlq",
            containerFactory = "webhookDlqKafkaListenerContainerFactory")
    public void onDlq(String messageJson) {
        try {
            DeliveryMessage message = objectMapper.readValue(messageJson, DeliveryMessage.class);
            if (repository.findByDeliveryId(message.deliveryId()).isPresent()) {
                log.debug(
                        "DLQ entry for delivery {} already present; skipping",
                        message.deliveryId());
                return;
            }
            // Reason/finalStatus default for direct DLQ publishes.
            dlqService.persist(message, "published_directly_to_dlq", "FAILED_PERMANENT");
        } catch (JsonProcessingException e) {
            log.error("Failed to process DLQ message: {}", messageJson, e);
        }
    }
}

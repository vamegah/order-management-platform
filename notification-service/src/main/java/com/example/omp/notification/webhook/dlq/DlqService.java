package com.example.omp.notification.webhook.dlq;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.error.WebhookExceptions;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and replays dead-lettered deliveries.
 *
 * <p>"Persist" is called by the delivery worker on permanent failure or when retries are exhausted.
 * It writes a webhook_delivery_dlq row and the delivery worker has already recorded the final
 * attempt.
 */
@Service
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);

    private final WebhookDlqRepository repository;
    private final WebhookDeliveryPublisher publisher;
    private final ObjectMapper objectMapper;
    private final WebhookMetrics metrics;

    public DlqService(
            WebhookDlqRepository repository,
            WebhookDeliveryPublisher publisher,
            ObjectMapper objectMapper,
            WebhookMetrics metrics) {
        this.repository = repository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public void persist(DeliveryMessage message, String reason, String finalStatus) {
        try {
            WebhookDlqEntry entry = new WebhookDlqEntry();
            entry.setDeliveryId(message.deliveryId());
            entry.setSubscriptionId(message.subscriptionId());
            entry.setEventId(message.eventId());
            entry.setEventType(message.eventType());
            entry.setPayload(objectMapper.writeValueAsString(message));
            entry.setReason(truncate(reason));
            entry.setFinalStatus(finalStatus);
            entry.setAttempts(message.attemptNumber());
            repository.save(entry);
            metrics.recordDlq(reason);
            log.info(
                    "DLQ: delivery {} ({}) from subscription {} — {}",
                    message.deliveryId(),
                    message.eventType(),
                    message.subscriptionId(),
                    reason);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize DLQ delivery", e);
        }
    }

    /**
     * Replays a DLQ entry. Resets attemptNumber to 1 and republishes to the main deliveries topic.
     * Marks the entry replayed.
     */
    @Transactional
    public void replay(UUID deliveryId, String replayedBy) {
        WebhookDlqEntry entry =
                repository
                        .findByDeliveryId(deliveryId)
                        .orElseThrow(() -> new WebhookExceptions.DlqEntryNotFound(deliveryId));

        if (entry.getReplayedAt() != null) {
            throw new WebhookExceptions.DlqAlreadyReplayed(deliveryId);
        }

        DeliveryMessage original = parseOriginal(entry);

        DeliveryMessage replayMessage =
                new DeliveryMessage(
                        original.deliveryId(),
                        original.subscriptionId(),
                        original.eventId(),
                        original.eventType(),
                        1,
                        null);

        publisher.publish(WebhookKafkaTopics.DELIVERIES, replayMessage);

        entry.setReplayedAt(Instant.now());
        entry.setReplayedBy(replayedBy);
        repository.save(entry);

        metrics.recordDlqReplay();
        log.info("Replayed DLQ entry {} by {}", deliveryId, replayedBy);
    }

    private DeliveryMessage parseOriginal(WebhookDlqEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), DeliveryMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Corrupt DLQ payload for delivery " + entry.getDeliveryId(), e);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}

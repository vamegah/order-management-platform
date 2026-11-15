package com.example.omp.notification.webhook.matcher;

import java.util.UUID;

/**
 * Kafka message payload for the outbound delivery pipeline.
 *
 * <p>deliveryId: stable across retries, deterministic on (subscriptionId, eventId). attemptNumber:
 * 1 on the initial delivery, incremented on each retry. scheduledAt: optional epoch millis; if set,
 * the worker waits until this time before attempting. Null on the initial delivery.
 */
public record DeliveryMessage(
        UUID deliveryId,
        UUID subscriptionId,
        String eventId,
        String eventType,
        int attemptNumber,
        Long scheduledAtMillis) {
    public void validate() {
        if (deliveryId == null
                || subscriptionId == null
                || eventId == null
                || eventId.isBlank()
                || eventId.length() > 255
                || eventType == null
                || eventType.isBlank()
                || eventType.length() > 100
                || attemptNumber < 1
                || attemptNumber > 6
                || (scheduledAtMillis != null
                        && scheduledAtMillis
                                > System.currentTimeMillis()
                                        + java.time.Duration.ofHours(30).toMillis()))
            throw new com.example.omp.shared.events.EventContract.InvalidEventException();
    }
}

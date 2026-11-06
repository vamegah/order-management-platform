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
        Long scheduledAtMillis) {}

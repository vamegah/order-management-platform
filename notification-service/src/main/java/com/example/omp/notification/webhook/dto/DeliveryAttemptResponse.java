package com.example.omp.notification.webhook.dto;

import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id,
        UUID deliveryId,
        UUID subscriptionId,
        String eventId,
        String eventType,
        int attemptNumber,
        String status,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"integer", "null"})
                Integer responseStatus,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String responseBody,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String errorMessage,
        Instant scheduledAt,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) Instant startedAt,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                Instant completedAt) {

    public static DeliveryAttemptResponse from(WebhookDeliveryAttempt a) {
        return new DeliveryAttemptResponse(
                a.getId(),
                a.getDeliveryId(),
                a.getSubscriptionId(),
                a.getEventId(),
                a.getEventType(),
                a.getAttemptNumber(),
                a.getStatus().name(),
                a.getResponseStatus(),
                a.getResponseBody(),
                a.getErrorMessage(),
                a.getScheduledAt(),
                a.getStartedAt(),
                a.getCompletedAt());
    }
}

package com.example.omp.notification.webhook.dto;

import com.example.omp.notification.webhook.WebhookSubscription;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        String ownerType,
        UUID ownerId,
        String name,
        String targetUrl,
        List<String> eventTypes,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        Instant lastDeliveryAt,
        int failureCount,
        Instant disabledAt,
        String disabledReason) {

    public static SubscriptionResponse from(WebhookSubscription s) {
        return new SubscriptionResponse(
                s.getId(),
                s.getOwnerType().dbValue(),
                s.getOwnerId(),
                s.getName(),
                s.getTargetUrl(),
                s.getEventTypes(),
                s.isEnabled(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getLastDeliveryAt(),
                s.getFailureCount(),
                s.getDisabledAt(),
                s.getDisabledReason());
    }
}

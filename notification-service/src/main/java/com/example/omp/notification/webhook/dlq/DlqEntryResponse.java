package com.example.omp.notification.webhook.dlq;

import java.time.Instant;
import java.util.UUID;

public record DlqEntryResponse(
        UUID id,
        UUID deliveryId,
        UUID subscriptionId,
        String subscriptionName,
        String eventId,
        String eventType,
        String reason,
        String finalStatus,
        int attempts,
        Instant receivedAt,
        Instant replayedAt,
        String replayedBy) {}

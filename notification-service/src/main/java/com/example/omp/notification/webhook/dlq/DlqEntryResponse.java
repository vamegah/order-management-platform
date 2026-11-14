package com.example.omp.notification.webhook.dlq;

import java.time.Instant;
import java.util.UUID;

public record DlqEntryResponse(
        UUID id,
        UUID deliveryId,
        UUID subscriptionId,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                String subscriptionName,
        String eventId,
        String eventType,
        String reason,
        String finalStatus,
        int attempts,
        Instant receivedAt,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) Instant replayedAt,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                String replayedBy) {}

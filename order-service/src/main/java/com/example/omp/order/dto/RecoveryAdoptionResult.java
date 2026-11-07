package com.example.omp.order.dto;

import java.time.Instant;
import java.util.UUID;

public record RecoveryAdoptionResult(
        UUID orderId,
        String status,
        boolean submitted,
        String requestedBy,
        String reason,
        Instant requestedAt) {}

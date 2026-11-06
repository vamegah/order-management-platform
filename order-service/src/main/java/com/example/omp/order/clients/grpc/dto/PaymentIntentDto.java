package com.example.omp.order.clients.grpc.dto;

import java.util.UUID;

public record PaymentIntentDto(
        UUID id,
        UUID orderId,
        UUID customerId,
        String amount,
        String currency,
        String status,
        String providerReference,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {}

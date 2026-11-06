package com.example.omp.order.clients.grpc.dto;

import java.util.UUID;

public record ReservationDto(
        UUID id,
        String sku,
        String warehouseId,
        int quantity,
        UUID orderId,
        String status,
        long createdAtEpochMillis,
        long updatedAtEpochMillis) {}

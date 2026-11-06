package com.example.omp.order.clients.grpc.dto;

public record StockLevelDto(
        String sku,
        String warehouseId,
        int quantityOnHand,
        int quantityReserved,
        int quantityAvailable,
        long lastUpdatedEpochMillis) {}

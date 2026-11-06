package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductClientDto(
        UUID id,
        String sku,
        String name,
        String description,
        CategorySummaryDto category,
        MoneyDto price,
        String status,
        List<ProductImageDto> images,
        Instant createdAt,
        Instant updatedAt) {
    public record CategorySummaryDto(UUID id, String name, String slug) {}

    public record MoneyDto(BigDecimal amount, String currency) {}

    public record ProductImageDto(UUID id, String url, String altText, int sortOrder) {}
}

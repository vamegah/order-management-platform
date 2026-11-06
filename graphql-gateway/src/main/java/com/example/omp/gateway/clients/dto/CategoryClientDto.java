package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** Mirror of product-service's {@code CategoryDto}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryClientDto(
        UUID id,
        String name,
        String slug,
        String description,
        CategorySummaryDto parent,
        Integer sortOrder) {

    /** Minimal category reference, as embedded in a product's response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategorySummaryDto(UUID id, String name, String slug) {}
}

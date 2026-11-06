package com.example.omp.order.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Product as returned by {@code product-service}.
 *
 * <p>This is a MIRROR of the corresponding DTO in product-service, not a shared type. order-service
 * declares only the fields it consumes. Other fields returned by product-service are ignored.
 *
 * <p>Why not a shared module? Because services evolve independently. A new field in product-service
 * must not require a coordinated release in order-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDto(
        UUID id, String sku, String name, String description, MoneyDto price, String status) {
    public record MoneyDto(BigDecimal amount, String currency) {}
}

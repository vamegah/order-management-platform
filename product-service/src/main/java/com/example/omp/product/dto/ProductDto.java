package com.example.omp.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A product as returned by the API.
 *
 * <p>Wire format for {@code GET /api/v1/products}, {@code GET /api/v1/products/{id}}, and the
 * response bodies of create/update operations.
 *
 * <p>Immutable (Java record). Jackson deserializes and serializes records natively.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A product in the catalog")
public record ProductDto(
        @Schema(
                        description = "Server-generated unique identifier",
                        example = "00000000-0000-0000-0000-000000000201")
                UUID id,
        @Schema(
                        description = "Stock Keeping Unit. Case-insensitive unique.",
                        example = "LAPTOP-PRO-14")
                String sku,
        @Schema(description = "Display name", example = "Pro Laptop 14\"") String name,
        @Schema(description = "Longer description", example = "High-performance 14-inch laptop...")
                String description,
        @Schema(description = "Category, or null if uncategorized") CategoryDto category,
        @Schema(description = "Price") MoneyDto price,
        @Schema(description = "Lifecycle status", example = "ACTIVE") String status,
        @Schema(description = "Product images, ordered by sortOrder") List<ProductImageDto> images,
        @Schema(description = "Creation timestamp (UTC)") Instant createdAt,
        @Schema(description = "Last modification timestamp (UTC)") Instant updatedAt) {}

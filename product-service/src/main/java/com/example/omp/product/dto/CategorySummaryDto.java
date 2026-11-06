package com.example.omp.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A minimal category reference (id, name, slug).
 *
 * <p>Used when a category is referenced from another resource (e.g., the {@code category} field of
 * a product) and the full category structure isn't needed.
 */
@Schema(description = "A minimal category reference")
public record CategorySummaryDto(
        @Schema(description = "Unique identifier") UUID id,
        @Schema(description = "Display name", example = "Laptops") String name,
        @Schema(description = "URL-friendly identifier", example = "laptops") String slug) {}

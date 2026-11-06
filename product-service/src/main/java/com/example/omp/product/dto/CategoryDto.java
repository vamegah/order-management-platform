package com.example.omp.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A category as returned by the API.
 *
 * <p>When embedded in a {@link ProductDto}, only the id/name/slug are populated (the
 * parent/children fields are null). When returned from {@code GET /api/v1/categories/{id}}, the
 * full structure is present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A product category")
public record CategoryDto(
        @Schema(description = "Server-generated unique identifier") UUID id,
        @Schema(description = "Display name", example = "Laptops") String name,
        @Schema(description = "URL-friendly identifier", example = "laptops") String slug,
        @Schema(description = "Optional description") String description,
        @Schema(description = "Parent category, or null for roots") CategorySummaryDto parent,
        @Schema(description = "Sort order among siblings") Integer sortOrder) {}

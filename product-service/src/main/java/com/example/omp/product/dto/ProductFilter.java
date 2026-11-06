package com.example.omp.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Query parameters for listing products.
 *
 * <p>All fields are optional. A null field means "don't filter on this attribute."
 */
@Schema(description = "Filter for product listing")
public record ProductFilter(
        @Schema(description = "Filter by category", example = "laptops") String categorySlug,
        @Schema(description = "Filter by category ID") UUID categoryId,
        @Schema(description = "Filter by status", example = "ACTIVE") String status,
        @Schema(description = "Search by name (case-insensitive substring)", example = "laptop")
                String nameQuery) {

    /** Returns a filter that matches everything (all fields null). */
    public static ProductFilter empty() {
        return new ProductFilter(null, null, null, null);
    }

    /** True if no filters are set. */
    public boolean isEmpty() {
        return categorySlug == null && categoryId == null && status == null && nameQuery == null;
    }
}

package com.example.omp.product.service;

import com.example.omp.product.domain.Category;
import com.example.omp.product.dto.CategoryDto;
import com.example.omp.product.dto.CategorySummaryDto;

/**
 * Converts between {@link Category} entities and DTOs.
 *
 * <p>Delegates most logic to {@link ProductMapper} (which has the canonical category mappings),
 * keeping this class as a thin alias for clarity of intent when mapping categories outside the
 * product context.
 */
public final class CategoryMapper {

    private CategoryMapper() {}

    public static CategoryDto toDto(Category category) {
        return ProductMapper.toDto(category);
    }

    public static CategorySummaryDto toSummary(Category category) {
        return ProductMapper.toSummary(category);
    }
}

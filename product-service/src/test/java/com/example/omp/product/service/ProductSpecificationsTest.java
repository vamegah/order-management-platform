package com.example.omp.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.product.dto.ProductFilter;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductSpecifications}.
 *
 * <p>SQL generation is tested via {@code ProductRepositoryIT}. This test focuses on the filter
 * composition logic: null handling, empty filters, and the "always-true" fallback.
 */
class ProductSpecificationsTest {

    @Test
    void emptyFilterProducesNoOpSpecification() {
        var spec = ProductSpecifications.fromFilter(ProductFilter.empty());
        assertThat(spec).isNotNull();
    }

    @Test
    void nullFilterProducesNoOpSpecification() {
        var spec = ProductSpecifications.fromFilter(null);
        assertThat(spec).isNotNull();
    }

    @Test
    void filterIsEmptyWhenAllFieldsNull() {
        ProductFilter filter = new ProductFilter(null, null, null, null);
        assertThat(filter.isEmpty()).isTrue();
    }

    @Test
    void filterIsNotEmptyWhenAnyFieldSet() {
        ProductFilter filter = new ProductFilter("laptops", null, null, null);
        assertThat(filter.isEmpty()).isFalse();
    }
}

package com.example.omp.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.omp.product.domain.Product;
import com.example.omp.product.dto.ProductFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
        assertThat(spec.toPredicate(null, null, null)).isNull();
    }

    @Test
    void nullFilterProducesNoOpSpecification() {
        var spec = ProductSpecifications.fromFilter(null);
        assertThat(spec).isNotNull();
        assertThat(spec.toPredicate(null, null, null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownStatusProducesAnAlwaysFalsePredicate() {
        Root<Product> root = mock(Root.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Predicate deny = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);
        when(builder.disjunction()).thenReturn(deny);
        when(builder.and(new Predicate[] {deny})).thenReturn(combined);

        var spec = ProductSpecifications.fromFilter(new ProductFilter(null, null, "unknown", null));

        assertThat(spec.toPredicate(root, null, builder)).isSameAs(combined);
        verify(builder).disjunction();
        verify(builder).and(new Predicate[] {deny});
        verifyNoInteractions(root);
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

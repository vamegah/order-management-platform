package com.example.omp.product.service;

import com.example.omp.product.domain.Product;
import com.example.omp.product.domain.ProductStatus;
import com.example.omp.product.dto.ProductFilter;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds JPA {@link Specification}s from a {@link ProductFilter}.
 *
 * <p>Specifications are composed into a single WHERE clause at query time. A null filter field
 * means "no constraint on this attribute."
 */
public final class ProductSpecifications {

    private ProductSpecifications() {}

    /**
     * Builds a specification from a filter. Returns a no-op specification if the filter is empty.
     */
    public static Specification<Product> fromFilter(ProductFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return Specification.unrestricted();
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Filter by category ID
            if (filter.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
            }

            // Filter by category slug
            if (filter.categorySlug() != null && !filter.categorySlug().isBlank()) {
                predicates.add(
                        cb.equal(
                                cb.lower(root.join("category", JoinType.LEFT).get("slug")),
                                filter.categorySlug().toLowerCase(Locale.ROOT)));
            }

            // Filter by status
            if (filter.status() != null && !filter.status().isBlank()) {
                try {
                    ProductStatus status =
                            ProductStatus.valueOf(filter.status().toUpperCase(Locale.ROOT));
                    predicates.add(cb.equal(root.get("status"), status));
                } catch (IllegalArgumentException e) {
                    // Unknown status → no results (safer than ignoring the filter)
                    predicates.add(cb.disjunction());
                }
            }

            // Filter by name (case-insensitive substring)
            if (filter.nameQuery() != null && !filter.nameQuery().isBlank()) {
                String pattern = "%" + filter.nameQuery().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("name")), pattern));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Limits results to ACTIVE products. Used as a default filter. */
    public static Specification<Product> active() {
        return (root, query, cb) -> cb.equal(root.get("status"), ProductStatus.ACTIVE);
    }
}

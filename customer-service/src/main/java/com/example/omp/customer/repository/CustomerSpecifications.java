package com.example.omp.customer.repository;

import com.example.omp.customer.domain.Customer;
import com.example.omp.customer.domain.CustomerStatus;
import com.example.omp.customer.dto.CustomerFilter;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds JPA {@link Specification}s from a {@link CustomerFilter}.
 *
 * <p>A null filter field means "no constraint on this attribute."
 */
public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /**
     * Builds a specification from a filter. Returns a no-op specification if the filter is empty.
     */
    public static Specification<Customer> fromFilter(CustomerFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return Specification.where(null);
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Filter by status
            if (filter.status() != null && !filter.status().isBlank()) {
                try {
                    CustomerStatus status =
                            CustomerStatus.valueOf(filter.status().toUpperCase(Locale.ROOT));
                    predicates.add(cb.equal(root.get("status"), status));
                } catch (IllegalArgumentException e) {
                    // Unknown status → no results (safer than ignoring the filter)
                    predicates.add(cb.disjunction());
                }
            }

            // Filter by name (first OR last name, case-insensitive substring)
            if (filter.nameQuery() != null && !filter.nameQuery().isBlank()) {
                String pattern = "%" + filter.nameQuery().toLowerCase(Locale.ROOT) + "%";
                predicates.add(
                        cb.or(
                                cb.like(cb.lower(root.get("firstName")), pattern),
                                cb.like(cb.lower(root.get("lastName")), pattern)));
            }

            // Filter by email domain
            if (filter.emailDomain() != null && !filter.emailDomain().isBlank()) {
                // Extract the domain from the citext email column and compare.
                // The cast to text is needed because citext doesn't support LIKE.
                String pattern = "%@" + filter.emailDomain().toLowerCase(Locale.ROOT);
                predicates.add(cb.like(cb.lower(root.get("email").as(String.class)), pattern));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Limits results to ACTIVE customers. */
    public static Specification<Customer> active() {
        return (root, query, cb) -> cb.equal(root.get("status"), CustomerStatus.ACTIVE);
    }
}

package com.example.omp.order.repository;

import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderStatus;
import com.example.omp.order.dto.OrderFilter;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Builds JPA {@link Specification}s from an {@link OrderFilter}. */
public final class OrderSpecifications {

    private OrderSpecifications() {}

    public static Specification<Order> fromFilter(OrderFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return Specification.unrestricted();
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.customerId() != null) {
                predicates.add(cb.equal(root.get("customerId"), filter.customerId()));
            }

            if (filter.status() != null && !filter.status().isBlank()) {
                try {
                    OrderStatus status =
                            OrderStatus.valueOf(filter.status().toUpperCase(Locale.ROOT));
                    predicates.add(cb.equal(root.get("status"), status));
                } catch (IllegalArgumentException e) {
                    predicates.add(cb.disjunction());
                }
            }

            if (filter.orderNumber() != null && !filter.orderNumber().isBlank()) {
                predicates.add(
                        cb.equal(
                                root.get("orderNumber").get("value"),
                                filter.orderNumber().toUpperCase(Locale.ROOT)));
            }

            if (filter.placedAfter() != null) {
                predicates.add(
                        cb.greaterThanOrEqualTo(
                                root.get("createdAt").as(Instant.class), filter.placedAfter()));
            }

            if (filter.placedBefore() != null) {
                predicates.add(
                        cb.lessThanOrEqualTo(
                                root.get("createdAt").as(Instant.class), filter.placedBefore()));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

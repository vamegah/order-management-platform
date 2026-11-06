package com.example.omp.order.repository;

import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderNumber;
import com.example.omp.order.domain.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link Order}.
 *
 * <p>Most lookups eager-load the line items (an order without its lines is rarely useful). Status
 * history is loaded only when explicitly requested.
 */
@Repository
public interface OrderRepository
        extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Finds by the human-readable order number. */
    Optional<Order> findByOrderNumber(OrderNumber orderNumber);

    boolean existsByIdAndCustomerId(UUID id, UUID customerId);

    boolean existsByOrderNumberAndCustomerId(OrderNumber orderNumber, UUID customerId);

    /** Finds by id, eager-loading line items. */
    @EntityGraph(attributePaths = {"lines"})
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLines(@Param("id") UUID id);

    /**
     * Loads lines; the transactional service initializes history with a second query to avoid
     * fetching two bags.
     */
    @EntityGraph(attributePaths = {"lines"})
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithFullDetails(@Param("id") UUID id);

    /** Lists orders for a customer, pageable. */
    @EntityGraph(attributePaths = {"lines"})
    Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    /** Lists orders by status, pageable. */
    @EntityGraph(attributePaths = {"lines"})
    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);
}

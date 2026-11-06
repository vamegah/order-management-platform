package com.example.omp.customer.repository;

import com.example.omp.customer.domain.Customer;
import com.example.omp.shared.domain.Email;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link Customer}.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the service layer can build dynamic filter
 * queries.
 */
@Repository
public interface CustomerRepository
        extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    @EntityGraph(attributePaths = {"addresses", "preferences"})
    java.util.List<Customer> findByIdIn(java.util.Collection<UUID> ids);

    /** Finds a customer by email (case-insensitive via the citext column). */
    Optional<Customer> findByEmail(Email email);

    /** True if a customer exists with this email. */
    boolean existsByEmail(Email email);

    /** Finds a customer by Keycloak {@code sub} claim. */
    Optional<Customer> findByKeycloakSub(String keycloakSub);

    /**
     * Finds a customer with addresses eagerly loaded. Used by endpoints that return the full
     * customer (including addresses).
     */
    @EntityGraph(attributePaths = {"addresses", "preferences"})
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdWithDetails(@Param("id") UUID id);

    /** Serializes changes to owned addresses/preferences without locking an outer join. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") UUID id);

    /** Finds a customer by email with details eager-loaded. */
    @EntityGraph(attributePaths = {"addresses", "preferences"})
    @Query("SELECT c FROM Customer c WHERE c.email = :email")
    Optional<Customer> findByEmailWithDetails(@Param("email") Email email);

    /** Lists active customers, pageable. Used by admin views. */
    @EntityGraph(attributePaths = {"preferences"})
    Page<Customer> findByStatus(String status, Pageable pageable);

    /**
     * Search by name (case-insensitive, first OR last name matching). Uses trigram indexes when the
     * pattern is broad enough.
     */
    @Query(
            """
        SELECT c FROM Customer c
        WHERE LOWER(c.firstName) LIKE LOWER(:pattern)
           OR LOWER(c.lastName)  LIKE LOWER(:pattern)
        """)
    Page<Customer> searchByName(@Param("pattern") String pattern, Pageable pageable);
}

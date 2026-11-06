package com.example.omp.product.repository;

import com.example.omp.product.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link Category}.
 *
 * <p>Spring Data JPA generates the implementation at runtime based on method names and
 * {@code @Query} annotations.
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** Finds a category by slug (case-insensitive, matching the citext column). */
    Optional<Category> findBySlug(String slug);

    /** Returns all root categories, sorted by sort_order. */
    List<Category> findByParentIsNullOrderBySortOrderAsc();

    /** Returns children of a specific parent, sorted by sort_order. */
    List<Category> findByParentIdOrderBySortOrderAsc(UUID parentId);

    /** True if a category with this slug exists. */
    boolean existsBySlug(String slug);

    /**
     * Returns all categories with their parent eagerly loaded. Useful for building a category tree
     * without N+1 queries.
     */
    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.sortOrder ASC")
    List<Category> findAllWithParent();
}

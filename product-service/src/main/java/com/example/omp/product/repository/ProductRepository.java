package com.example.omp.product.repository;

import com.example.omp.product.domain.Product;
import com.example.omp.product.domain.ProductStatus;
import com.example.omp.product.domain.Sku;
import java.util.List;
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
 * Repository for {@link Product}.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the service layer can build dynamic filter queries
 * (used in Batch 1.3 for catalog search).
 */
@Repository
public interface ProductRepository
        extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    @EntityGraph(attributePaths = {"category", "images"})
    List<Product> findByIdIn(java.util.Collection<UUID> ids);

    /** Finds a product by SKU (case-insensitive). */
    Optional<Product> findBySku(Sku sku);

    /** True if a product with this SKU exists. */
    boolean existsBySku(Sku sku);

    /**
     * Lists products in a category with a given status, pageable. Uses {@code @EntityGraph} to
     * fetch the category eagerly — otherwise accessing {@code product.getCategory().getName()}
     * would trigger an N+1 on each row.
     */
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByCategoryIdAndStatus(
            UUID categoryId, ProductStatus status, Pageable pageable);

    /** Lists all products with a status, pageable. */
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    /**
     * Full-text-ish search by name using Postgres's trigram similarity. The {@code ILIKE} uses the
     * {@code idx_product_name_trgm} GIN index (see V1) — no full-table scan for common patterns.
     *
     * @param pattern a LIKE pattern, e.g. {@code "%laptop%"}
     */
    @Query(
            """
        SELECT p FROM Product p
        LEFT JOIN FETCH p.category
        WHERE p.status = 'ACTIVE'
          AND LOWER(p.name) LIKE LOWER(:pattern)
        """)
    List<Product> searchByName(@Param("pattern") String pattern);

    /**
     * Counts products in a category. Used by the category listing endpoint to render "N products"
     * without loading all of them.
     */
    long countByCategoryId(UUID categoryId);
}

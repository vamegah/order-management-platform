package com.example.omp.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.domain.Category;
import com.example.omp.product.domain.Product;
import com.example.omp.product.domain.ProductStatus;
import com.example.omp.product.domain.Sku;
import com.example.omp.shared.domain.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link ProductRepository} against a real Postgres.
 *
 * <p>Each test runs in a transaction that rolls back — no cleanup needed. The V2 seed data is
 * present, so tests can rely on the seed SKUs existing.
 */
@Transactional
class ProductRepositoryIT extends AbstractIntegrationTest {

    @Autowired private ProductRepository productRepository;

    @Autowired private CategoryRepository categoryRepository;

    // -------------------------------------------------------------------------
    // SKU lookups
    // -------------------------------------------------------------------------

    @Test
    void findBySkuIsCaseInsensitive() {
        Optional<Product> lower = productRepository.findBySku(Sku.of("laptop-pro-14"));
        Optional<Product> upper = productRepository.findBySku(Sku.of("LAPTOP-PRO-14"));

        assertThat(lower).isPresent();
        assertThat(upper).isPresent();
        assertThat(lower.get().getId()).isEqualTo(upper.get().getId());
    }

    @Test
    void existsBySkuReturnsTrueForSeedData() {
        assertThat(productRepository.existsBySku(Sku.of("LAPTOP-PRO-14"))).isTrue();
    }

    @Test
    void existsBySkuReturnsFalseForUnknown() {
        assertThat(productRepository.existsBySku(Sku.of("DOES-NOT-EXIST"))).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category queries
    // -------------------------------------------------------------------------

    @Test
    void findByCategoryIdAndStatusReturnsActiveProducts() {
        // Seed: Laptops category has 3 products (2 active, 1 discontinued).
        Optional<Category> laptops = categoryRepository.findBySlug("laptops");
        assertThat(laptops).isPresent();

        var page =
                productRepository.findByCategoryIdAndStatus(
                        laptops.get().getId(),
                        ProductStatus.ACTIVE,
                        org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent())
                .extracting(p -> p.getSku().value())
                .containsExactlyInAnyOrder("LAPTOP-PRO-14", "LAPTOP-AIR-13");
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    @Test
    void searchByNameMatchesSubstring() {
        List<Product> results = productRepository.searchByName("%laptop%");
        assertThat(results)
                .extracting(p -> p.getSku().value())
                .contains("LAPTOP-PRO-14", "LAPTOP-AIR-13");
    }

    @Test
    void searchByNameIsCaseInsensitive() {
        List<Product> lower = productRepository.searchByName("%laptop%");
        List<Product> upper = productRepository.searchByName("%LAPTOP%");
        assertThat(lower).hasSameSizeAs(upper);
    }

    @Test
    void searchByNameExcludesDiscontinued() {
        List<Product> results = productRepository.searchByName("%Legacy%");
        assertThat(results).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    @Test
    void saveAssignsIdAndTimestamps() {
        Category category = categoryRepository.findBySlug("laptops").orElseThrow();
        Product product =
                new Product(
                        Sku.of("TEST-SAVE-001"), "Test Save", "desc", category, Money.usd("1.00"));
        Product saved = productRepository.saveAndFlush(product);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}

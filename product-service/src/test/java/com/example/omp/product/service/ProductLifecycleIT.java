package com.example.omp.product.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.product.AbstractIntegrationTest;
import com.example.omp.product.domain.Category;
import com.example.omp.product.domain.Product;
import com.example.omp.product.dto.*;
import com.example.omp.product.error.*;
import com.example.omp.product.repository.CategoryRepository;
import com.example.omp.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class ProductLifecycleIT extends AbstractIntegrationTest {
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-000000000102");
    @Autowired ProductService products;
    @Autowired ProductRepository repository;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void imageReplacementCommitsNewOrderAndDeletesOldRows() {
        var created =
                products.create(
                        new CreateProductRequest(
                                "images-lifecycle",
                                "Lifecycle Images",
                                "Original",
                                CATEGORY,
                                new CreateProductRequest.CreatePriceRequest(
                                        new BigDecimal("19.99"), "USD"),
                                List.of(
                                        new CreateProductRequest.CreateImageRequest(
                                                "https://images.example/first", "First"),
                                        new CreateProductRequest.CreateImageRequest(
                                                "https://images.example/second", "Second"))));
        assertThat(products.getBySku("images-lifecycle").id()).isEqualTo(created.id());
        var original = products.getById(created.id()).images();
        assertThat(original).extracting(ProductImageDto::sortOrder).containsExactly(10, 20);
        var updated =
                products.update(
                        created.id(),
                        new UpdateProductRequest(
                                "Lifecycle Images Updated",
                                null,
                                CATEGORY,
                                new UpdateProductRequest.UpdatePriceRequest(
                                        new BigDecimal("21.50"), "EUR"),
                                List.of(
                                        new UpdateProductRequest.UpdateImageRequest(
                                                "https://images.example/replacement",
                                                "Replacement"))));
        var stored = products.getById(created.id());
        assertThat(updated.images().get(0).id()).isNotNull();
        assertThat(stored.sku()).isEqualTo(created.sku());
        assertThat(stored.price().currency()).isEqualTo("EUR");
        assertThat(stored.images()).hasSize(1);
        assertThat(stored.images().get(0).url()).isEqualTo("https://images.example/replacement");
        assertThat(stored.images().get(0).id()).isNotNull();
        for (var image : original) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from products.product_image where id = ?",
                                    Integer.class,
                                    image.id()))
                    .isZero();
        }
        products.update(
                created.id(),
                new UpdateProductRequest(
                        updated.name(),
                        null,
                        CATEGORY,
                        new UpdateProductRequest.UpdatePriceRequest(new BigDecimal("21.50"), "EUR"),
                        List.of()));
        assertThat(products.getById(created.id()).images()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from products.product_image where product_id = ?",
                                Integer.class,
                                created.id()))
                .isZero();
    }

    @Test
    void filterCombinesCategoryIdentityStatusAndCaseInsensitiveName() {
        var filter = new ProductFilter("LAPTOPS", CATEGORY, "active", "PRO");
        var found = products.list(filter, PageRequest.of(0, 100)).content();
        assertThat(found)
                .isNotEmpty()
                .allSatisfy(
                        p -> {
                            assertThat(p.category().id()).isEqualTo(CATEGORY);
                            assertThat(p.status()).isEqualTo("ACTIVE");
                            assertThat(p.name()).containsIgnoringCase("pro");
                        });
        assertThat(
                        products.list(
                                        new ProductFilter(null, null, "unknown", null),
                                        PageRequest.of(0, 100))
                                .content())
                .isEmpty();
    }

    @Test
    void discontinuedProductCannotBeDeactivatedAndIsExcludedFromDefaultListing() {
        var created =
                products.create(
                        new CreateProductRequest(
                                "status-lifecycle",
                                "Lifecycle Status",
                                null,
                                CATEGORY,
                                new CreateProductRequest.CreatePriceRequest(BigDecimal.ONE, "USD"),
                                List.of()));
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> repository.findById(created.id()).orElseThrow().discontinue());
        assertThatThrownBy(() -> products.deactivate(created.id()))
                .isInstanceOf(InvalidProductDataException.class);
        assertThat(products.getById(created.id()).status()).isEqualTo("DISCONTINUED");
        assertThat(
                        products.list(
                                        new ProductFilter(null, null, null, "Lifecycle Status"),
                                        PageRequest.of(0, 100))
                                .content())
                .isEmpty();
        assertThat(
                        products.list(
                                        new ProductFilter(
                                                null, null, "DISCONTINUED", "Lifecycle Status"),
                                        PageRequest.of(0, 100))
                                .content())
                .extracting(ProductDto::id)
                .containsExactly(created.id());
    }

    @Test
    void categoryHierarchyAndImageOwnershipSurviveSeparateTransactions() {
        var tx = new TransactionTemplate(transactions);
        var child =
                tx.execute(
                        status -> {
                            var parent =
                                    categories.save(
                                            new Category(
                                                    "Lifecycle Root", "lifecycle-root", "Root"));
                            var nested =
                                    new Category("Lifecycle Child", "lifecycle-child", "Child");
                            nested.setParent(parent);
                            return categories.saveAndFlush(nested);
                        });
        assertThat(child.getCreatedAt()).isNotNull();
        tx.executeWithoutResult(
                status -> {
                    var loaded = categories.findById(child.getId()).orElseThrow();
                    assertThat(loaded.getParent().getChildren()).contains(child);
                    loaded.setName("Updated Child");
                    loaded.setSlug("updated-child");
                    loaded.setDescription("Updated description");
                    loaded.setSortOrder(42);
                });
        tx.executeWithoutResult(
                status -> {
                    var loaded = categories.findById(child.getId()).orElseThrow();
                    var mapped = CategoryMapper.toDto(loaded);
                    assertThat(mapped.name()).isEqualTo("Updated Child");
                    assertThat(mapped.slug()).isEqualTo("updated-child");
                    assertThat(mapped.description()).isEqualTo("Updated description");
                    assertThat(mapped.sortOrder()).isEqualTo(42);
                    assertThat(mapped.parent().slug()).isEqualTo("lifecycle-root");
                    assertThat(CategoryMapper.toSummary(loaded).slug()).isEqualTo(mapped.slug());
                    assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
                });
        var created =
                products.create(
                        new CreateProductRequest(
                                "image-ownership",
                                "Image Ownership",
                                null,
                                child.getId(),
                                new CreateProductRequest.CreatePriceRequest(BigDecimal.ONE, "USD"),
                                List.of(
                                        new CreateProductRequest.CreateImageRequest(
                                                "https://images.example/owned", "Owned"))));
        Product detached =
                tx.execute(status -> repository.findByIdIn(List.of(created.id())).getFirst());
        var detachedImage = detached.getImages().getFirst();
        tx.executeWithoutResult(
                status -> {
                    Product loaded = repository.findByIdIn(List.of(created.id())).getFirst();
                    assertThat(new java.util.HashSet<>(List.of(loaded))).contains(detached);
                    assertThat(new java.util.HashSet<>(loaded.getImages())).contains(detachedImage);
                    var image = loaded.getImages().getFirst();
                    assertThat(image.getProduct().getId()).isEqualTo(created.id());
                    assertThat(image.getCreatedAt()).isNotNull();
                    loaded.removeImage(image);
                    assertThat(image.getProduct()).isNull();
                });
        assertThat(products.getById(created.id()).images()).isEmpty();
    }

    @Test
    void blankSearchDoesNotExposeTheWholeCatalogue() {
        assertThat(products.search(null)).isEmpty();
        assertThat(products.search("  ")).isEmpty();
    }

    @Test
    void invalidReferencesDoNotModifyExistingProduct() {
        var existing = products.getBySku("LAPTOP-PRO-14");
        assertThatThrownBy(
                        () ->
                                products.update(
                                        existing.id(),
                                        new UpdateProductRequest(
                                                "Wrong",
                                                "Wrong",
                                                UUID.randomUUID(),
                                                new UpdateProductRequest.UpdatePriceRequest(
                                                        BigDecimal.ONE, "USD"),
                                                List.of())))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThat(products.getById(existing.id()).name()).isEqualTo(existing.name());
        assertThatThrownBy(() -> products.getBySku("MISSING-SKU"))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> products.deactivate(UUID.randomUUID()))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                products.update(
                                        UUID.randomUUID(),
                                        new UpdateProductRequest(
                                                "Missing",
                                                null,
                                                CATEGORY,
                                                new UpdateProductRequest.UpdatePriceRequest(
                                                        BigDecimal.ONE, "USD"),
                                                List.of())))
                .isInstanceOf(ProductNotFoundException.class);
    }
}

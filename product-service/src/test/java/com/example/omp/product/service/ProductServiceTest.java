package com.example.omp.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.omp.product.domain.Category;
import com.example.omp.product.domain.Product;
import com.example.omp.product.domain.Sku;
import com.example.omp.product.dto.CreateProductRequest;
import com.example.omp.product.dto.ProductDto;
import com.example.omp.product.error.CategoryNotFoundException;
import com.example.omp.product.error.ProductNotFoundException;
import com.example.omp.product.error.SkuAlreadyExistsException;
import com.example.omp.product.repository.CategoryRepository;
import com.example.omp.product.repository.ProductRepository;
import com.example.omp.shared.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ProductService}. Repositories are mocked; tests exercise business rules in
 * isolation.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Test
    void batchPreservesOrderDeduplicatesAndOmitsMissingProducts() {
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID(), missing = UUID.randomUUID();
        var first = new Product(Sku.of("BATCH-1"), "First", "", category, Money.usd("1"));
        var second = new Product(Sku.of("BATCH-2"), "Second", "", category, Money.usd("2"));
        org.springframework.test.util.ReflectionTestUtils.setField(first, "id", firstId);
        org.springframework.test.util.ReflectionTestUtils.setField(second, "id", secondId);
        var ids = List.of(secondId, firstId, missing, secondId);
        when(productRepository.findByIdIn(ids)).thenReturn(List.of(first, second));
        assertThat(productService.getBatch(ids))
                .extracting(ProductDto::id)
                .containsExactly(secondId, firstId);
        verify(productRepository).findByIdIn(ids);
    }

    @Test
    void oversizedBatchIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(
                        () ->
                                productService.getBatch(
                                        java.util.Collections.nCopies(101, UUID.randomUUID())))
                .isInstanceOf(com.example.omp.product.error.InvalidProductDataException.class);
        org.mockito.Mockito.verifyNoInteractions(productRepository);
    }

    @Mock private ProductRepository productRepository;

    @Mock private CategoryRepository categoryRepository;

    @InjectMocks private ProductService productService;

    private UUID categoryId;
    private Category category;

    @BeforeEach
    void setUp() {
        categoryId = UUID.randomUUID();
        category = new Category("Laptops", "laptops", "Portable computers");
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    void createThrowsWhenSkuAlreadyExists() {
        var request = newRequest("EXISTING-001", categoryId);

        when(productRepository.existsBySku(any(Sku.class))).thenReturn(true);

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(SkuAlreadyExistsException.class)
                .hasMessageContaining("EXISTING-001");

        verify(productRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenCategoryMissing() {
        var request = newRequest("NEW-001", categoryId);

        when(productRepository.existsBySku(any(Sku.class))).thenReturn(false);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void createPersistsAndReturnsDto() {
        var request = newRequest("NEW-001", categoryId);

        when(productRepository.existsBySku(any(Sku.class))).thenReturn(false);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductDto result = productService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.sku()).isEqualTo("NEW-001");
        assertThat(result.name()).isEqualTo("New Product");
        assertThat(result.price().amount()).isEqualByComparingTo("99.99");
        assertThat(result.price().currency()).isEqualTo("USD");

        verify(productRepository).save(any(Product.class));
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    @Test
    void getByIdThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(id))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getByIdReturnsDto() {
        UUID id = UUID.randomUUID();
        Sku sku = Sku.of("TEST-001");
        Product product = new Product(sku, "Test Product", "desc", category, Money.usd("9.99"));
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        ProductDto result = productService.getById(id);

        assertThat(result.sku()).isEqualTo("TEST-001");
    }

    // -------------------------------------------------------------------------
    // search
    // -------------------------------------------------------------------------

    @Test
    void searchReturnsEmptyForBlankQuery() {
        assertThat(productService.search("")).isEmpty();
        assertThat(productService.search(null)).isEmpty();
        verify(productRepository, never()).searchByName(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CreateProductRequest newRequest(String sku, UUID categoryId) {
        return new CreateProductRequest(
                sku,
                "New Product",
                "Description",
                categoryId,
                new CreateProductRequest.CreatePriceRequest(new BigDecimal("99.99"), "USD"),
                List.of());
    }
}

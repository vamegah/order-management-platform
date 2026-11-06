package com.example.omp.product.service;

import com.example.omp.product.domain.Category;
import com.example.omp.product.domain.Product;
import com.example.omp.product.domain.ProductStatus;
import com.example.omp.product.domain.Sku;
import com.example.omp.product.dto.CategoryDto;
import com.example.omp.product.dto.CreateProductRequest;
import com.example.omp.product.dto.ProductDto;
import com.example.omp.product.dto.ProductFilter;
import com.example.omp.product.dto.UpdateProductRequest;
import com.example.omp.product.error.CategoryNotFoundException;
import com.example.omp.product.error.InvalidProductDataException;
import com.example.omp.product.error.ProductNotFoundException;
import com.example.omp.product.error.SkuAlreadyExistsException;
import com.example.omp.product.repository.CategoryRepository;
import com.example.omp.product.repository.ProductRepository;
import com.example.omp.shared.domain.Money;
import com.example.omp.shared.web.PageResponse;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the product catalog.
 *
 * <p>All public methods return DTOs (never entities) and throw domain exceptions (never framework
 * exceptions) for business rule violations.
 *
 * <p>Read methods are {@code readOnly = true} so Hibernate skips dirty checking and can route to
 * read replicas (Phase 3+).
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(
            ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /** Returns found products in first-requested order, omitting missing IDs and duplicates. */
    @Transactional(readOnly = true)
    public List<ProductDto> getBatch(List<UUID> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || ids.stream().anyMatch(java.util.Objects::isNull))
            throw new InvalidProductDataException("Batch must contain 1 to 100 non-null IDs");
        var found =
                productRepository.findByIdIn(ids).stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Product::getId, ProductMapper::toDto));
        return ids.stream().distinct().filter(found::containsKey).map(found::get).toList();
    }

    /** Lists products matching the filter, pageable. */
    @Transactional(readOnly = true)
    public PageResponse<ProductDto> list(ProductFilter filter, Pageable pageable) {
        Specification<Product> spec = ProductSpecifications.fromFilter(filter);
        if (filter == null || filter.status() == null || filter.status().isBlank()) {
            spec = spec.and(ProductSpecifications.active());
        }
        Page<Product> page = productRepository.findAll(spec, pageable);
        return PageResponse.from(page, ProductMapper::toDto);
    }

    /**
     * Fetches a product by ID.
     *
     * @throws ProductNotFoundException if no product exists with this ID
     */
    @Transactional(readOnly = true)
    public ProductDto getById(UUID id) {
        Product product =
                productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        return ProductMapper.toDto(product);
    }

    /**
     * Fetches a product by SKU.
     *
     * @throws ProductNotFoundException if no product exists with this SKU
     */
    @Transactional(readOnly = true)
    public ProductDto getBySku(String sku) {
        Sku skuVo = Sku.of(sku);
        Product product =
                productRepository
                        .findBySku(skuVo)
                        .orElseThrow(() -> new ProductNotFoundException(sku));
        return ProductMapper.toDto(product);
    }

    /** Searches products by name (case-insensitive substring). */
    @Transactional(readOnly = true)
    public List<ProductDto> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String pattern = "%" + query.trim() + "%";
        return productRepository.searchByName(pattern).stream().map(ProductMapper::toDto).toList();
    }

    /** Lists all categories (flat). */
    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories() {
        return categoryRepository.findAllWithParent().stream().map(ProductMapper::toDto).toList();
    }

    /**
     * Fetches a category by slug.
     *
     * @throws CategoryNotFoundException if no category exists with this slug
     */
    @Transactional(readOnly = true)
    public CategoryDto getCategoryBySlug(String slug) {
        Category category =
                categoryRepository
                        .findBySlug(slug)
                        .orElseThrow(() -> new CategoryNotFoundException(slug));
        return ProductMapper.toDto(category);
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    /**
     * Creates a new product.
     *
     * @throws SkuAlreadyExistsException if the SKU is already in use
     * @throws CategoryNotFoundException if the referenced category doesn't exist
     */
    @Transactional
    public ProductDto create(CreateProductRequest request) {
        Sku sku = Sku.of(request.sku());

        if (productRepository.existsBySku(sku)) {
            throw new SkuAlreadyExistsException(sku.value());
        }

        Category category =
                categoryRepository
                        .findById(request.categoryId())
                        .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));

        Product product = ProductMapper.fromCreateRequest(request, category);
        Product saved = productRepository.save(product);

        log.info("Created product id={} sku={}", saved.getId(), saved.getSku());

        return ProductMapper.toDto(saved);
    }

    /**
     * Fully replaces the mutable fields of a product.
     *
     * @throws ProductNotFoundException if the product doesn't exist
     * @throws CategoryNotFoundException if the referenced category doesn't exist
     */
    @Transactional
    public ProductDto update(UUID id, UpdateProductRequest request) {
        Product product =
                productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));

        Category category =
                categoryRepository
                        .findById(request.categoryId())
                        .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));

        product.setName(request.name());
        product.setDescription(request.description());
        product.setCategory(category);
        product.setPrice(
                Money.of(
                        request.price().amount(),
                        Currency.getInstance(request.price().currency())));

        // Replace images entirely
        product.getImages().clear();
        if (request.images() != null) {
            int order = 10;
            for (UpdateProductRequest.UpdateImageRequest img : request.images()) {
                product.addImage(
                        new com.example.omp.product.domain.ProductImage(
                                img.url(), img.altText(), order));
                order += 10;
            }
        }

        // Assign identifiers to replacement images before constructing the response DTO.
        productRepository.flush();
        log.info("Updated product id={}", id);

        return ProductMapper.toDto(product);
    }

    /**
     * Marks a product inactive.
     *
     * @throws ProductNotFoundException if the product doesn't exist
     */
    @Transactional
    public void deactivate(UUID id) {
        Product product =
                productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        if (product.getStatus() == ProductStatus.DISCONTINUED) {
            throw new InvalidProductDataException(
                    "Cannot deactivate a discontinued product: " + id);
        }
        product.deactivate();
        log.info("Deactivated product id={}", id);
    }
}

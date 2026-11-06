package com.example.omp.product.service;

import com.example.omp.product.domain.Category;
import com.example.omp.product.domain.Product;
import com.example.omp.product.domain.ProductImage;
import com.example.omp.product.domain.Sku;
import com.example.omp.product.dto.CategoryDto;
import com.example.omp.product.dto.CategorySummaryDto;
import com.example.omp.product.dto.CreateProductRequest;
import com.example.omp.product.dto.MoneyDto;
import com.example.omp.product.dto.ProductDto;
import com.example.omp.product.dto.ProductImageDto;
import com.example.omp.shared.domain.Money;
import java.util.Currency;
import java.util.List;

/**
 * Converts between domain entities and DTOs.
 *
 * <p>Static methods: mapping is stateless, no need for a Spring bean.
 *
 * <p>All methods are null-safe — passing a null entity returns null.
 */
public final class ProductMapper {

    private ProductMapper() {
        // utility class
    }

    // -------------------------------------------------------------------------
    // Entity -> DTO
    // -------------------------------------------------------------------------

    /**
     * Converts a product to its full DTO representation.
     *
     * <p>Accesses lazy associations ({@code category}, {@code images}). The caller must ensure the
     * entity is loaded within an active transaction.
     */
    public static ProductDto toDto(Product product) {
        if (product == null) {
            return null;
        }

        List<ProductImageDto> images =
                product.getImages().stream().map(ProductMapper::toImageDto).toList();

        return new ProductDto(
                product.getId(),
                product.getSku().value(),
                product.getName(),
                product.getDescription(),
                product.getCategory() == null
                        ? null
                        : new CategoryDto(
                                product.getCategory().getId(),
                                product.getCategory().getName(),
                                product.getCategory().getSlug(),
                                null,
                                null,
                                null),
                toMoneyDto(product.getPrice()),
                product.getStatus().name(),
                images,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    /** Converts a category to its full DTO. */
    public static CategoryDto toDto(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                toSummary(category.getParent()),
                category.getSortOrder());
    }

    /** Converts a category to a minimal summary DTO. */
    public static CategorySummaryDto toSummary(Category category) {
        if (category == null) {
            return null;
        }
        return new CategorySummaryDto(category.getId(), category.getName(), category.getSlug());
    }

    /** Converts a product image to its DTO. */
    public static ProductImageDto toImageDto(ProductImage image) {
        if (image == null) {
            return null;
        }
        return new ProductImageDto(
                image.getId(), image.getUrl(), image.getAltText(), image.getSortOrder());
    }

    /** Converts a money value object to its DTO. */
    public static MoneyDto toMoneyDto(Money money) {
        if (money == null) {
            return null;
        }
        return new MoneyDto(money.getAmount(), money.getCurrencyCode());
    }

    // -------------------------------------------------------------------------
    // Request -> Entity
    // -------------------------------------------------------------------------

    /**
     * Builds a new product from a create request.
     *
     * <p>The category is passed separately because the service resolves the category ID to a {@link
     * Category} entity (with proper error handling if not found).
     */
    public static Product fromCreateRequest(CreateProductRequest request, Category category) {
        Sku sku = Sku.of(request.sku());
        Money price =
                Money.of(
                        request.price().amount(), Currency.getInstance(request.price().currency()));
        Product product = new Product(sku, request.name(), request.description(), category, price);

        if (request.images() != null) {
            int order = 10;
            for (CreateProductRequest.CreateImageRequest imgRequest : request.images()) {
                product.addImage(new ProductImage(imgRequest.url(), imgRequest.altText(), order));
                order += 10;
            }
        }
        return product;
    }
}

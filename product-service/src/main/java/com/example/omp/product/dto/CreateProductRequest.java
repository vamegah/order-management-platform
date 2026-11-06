package com.example.omp.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request body for creating a product.
 *
 * <p>All fields required unless noted. Field-level constraints are enforced by Spring's validation
 * before the service method runs.
 */
@Schema(description = "Request body for creating a product")
public record CreateProductRequest(
        @NotBlank
                @Size(min = 4, max = 64)
                @Pattern(
                        regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{3,63}$",
                        message =
                                "SKU must start with a letter or digit and contain only letters, digits, hyphens, and underscores")
                @Schema(
                        description = "Stock Keeping Unit. Will be normalized to uppercase.",
                        example = "LAPTOP-PRO-14")
                String sku,
        @NotBlank
                @Size(max = 300)
                @Schema(description = "Display name", example = "Pro Laptop 14\"")
                String name,
        @Size(max = 4000) @Schema(description = "Optional description") String description,
        @NotNull @Schema(description = "Category ID. Must reference an existing category.")
                UUID categoryId,
        @NotNull @Valid @Schema(description = "Price") CreatePriceRequest price,
        @Schema(description = "Optional images. Ordering is by position in the list.")
                List<@Valid CreateImageRequest> images) {

    @Schema(description = "Price for a new product")
    public record CreatePriceRequest(
            @NotNull @Schema(description = "Amount (must be non-negative)", example = "2499.99")
                    BigDecimal amount,
            @NotBlank
                    @Pattern(
                            regexp = "^[A-Z]{3}$",
                            message = "Must be a 3-letter ISO 4217 currency code")
                    @Schema(description = "ISO 4217 currency code", example = "USD")
                    String currency) {}

    @Schema(description = "Image for a new product")
    public record CreateImageRequest(
            @NotBlank @Size(max = 2000) @Schema(description = "Image URL") String url,
            @Size(max = 500) @Schema(description = "Alt text") String altText) {}
}

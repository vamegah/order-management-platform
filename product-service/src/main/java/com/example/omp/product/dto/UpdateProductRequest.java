package com.example.omp.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request body for updating a product.
 *
 * <p>Full replacement of mutable fields. Fields not present in this request (like SKU and id)
 * cannot be changed via update.
 */
@Schema(description = "Request body for updating a product")
public record UpdateProductRequest(
        @NotBlank
                @Size(max = 300)
                @Schema(description = "Display name", example = "Pro Laptop 14\" (2025)")
                String name,
        @Size(max = 4000) @Schema(description = "Description") String description,
        @NotNull @Schema(description = "Category ID") UUID categoryId,
        @NotNull @Valid @Schema(description = "Price") UpdatePriceRequest price,
        @Schema(description = "Replacement image list. Pass empty list to remove all images.")
                List<@Valid UpdateImageRequest> images) {

    @Schema(description = "Price update")
    public record UpdatePriceRequest(
            @NotNull @Schema(description = "Amount", example = "2399.99") BigDecimal amount,
            @NotBlank @Schema(description = "Currency code", example = "USD") String currency) {}

    @Schema(description = "Image update")
    public record UpdateImageRequest(
            @NotBlank @Schema(description = "Image URL") String url,
            @Schema(description = "Alt text") String altText) {}
}

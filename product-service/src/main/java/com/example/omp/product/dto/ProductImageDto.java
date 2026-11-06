package com.example.omp.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A product image. */
@Schema(description = "A product image")
public record ProductImageDto(
        @Schema(description = "Server-generated unique identifier") UUID id,
        @Schema(
                        description = "Image URL",
                        example = "https://cdn.example.com/products/pro-laptop-14-front.jpg")
                String url,
        @Schema(description = "Alt text for accessibility") String altText,
        @Schema(description = "Sort order (lower = shown first)") int sortOrder) {}

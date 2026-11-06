package com.example.omp.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * A monetary amount and its currency.
 *
 * <p>Wire format for the {@code price} field on products.
 */
@Schema(description = "A monetary amount in a specific currency")
public record MoneyDto(
        @Schema(description = "Amount with 4 decimal places", example = "2499.9900")
                BigDecimal amount,
        @Schema(description = "ISO 4217 currency code", example = "USD") String currency) {}

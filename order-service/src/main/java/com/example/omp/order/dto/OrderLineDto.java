package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "An order line")
public record OrderLineDto(
        @Schema(description = "Server-generated id") UUID id,
        @Schema(description = "Product reference id") UUID productId,
        @Schema(description = "Product SKU at order time") String productSku,
        @Schema(description = "Product name at order time") String productName,
        @Schema(description = "Unit price at order time") MoneyDto unitPrice,
        @Schema(description = "Quantity") int quantity,
        @Schema(description = "Line total (unit price × quantity)") BigDecimal lineTotal,
        @Schema(description = "Durable inventory reservation reference; absent on historical lines")
                @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                UUID reservationId) {
    public record MoneyDto(BigDecimal amount, String currency) {}
}

package com.example.omp.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An order")
public record OrderDto(
        @Schema(description = "Server-generated id") UUID id,
        @Schema(
                        description = "Business identifier (ORD-YYYY-NNNNNNN)",
                        example = "ORD-2025-0001234")
                String orderNumber,
        @Schema(description = "Customer id") UUID customerId,
        @Schema(description = "Customer email snapshot") String customerEmail,
        @Schema(
                        description = "Current status",
                        example = "PLACED",
                        implementation = com.example.omp.order.domain.OrderStatus.class)
                String status,
        @Schema(description = "Subtotal amount") MoneyDto subtotal,
        @Schema(description = "Tax amount") MoneyDto tax,
        @Schema(description = "Shipping amount") MoneyDto shipping,
        @Schema(description = "Total amount") MoneyDto total,
        @Schema(description = "Shipping address snapshot") ShippingAddressDto shippingAddress,
        @Schema(description = "Order lines") List<OrderLineDto> lines,
        @Schema(description = "Status history (only included in detail view)")
                List<OrderStatusHistoryDto> statusHistory,
        @Schema(description = "When the order was placed (null while DRAFT)") Instant placedAt,
        @Schema(description = "Customer-provided notes") String notes,
        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last modification timestamp") Instant updatedAt,
        @Schema(description = "Durable payment intent reference; absent on historical orders")
                UUID paymentId) {
    @Schema(description = "Money amount")
    public record MoneyDto(BigDecimal amount, String currency) {}

    @Schema(description = "Shipping address snapshot")
    public record ShippingAddressDto(
            String line1,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String line2,
            String city,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String state,
            String postalCode,
            String country) {}

    @Schema(description = "Status history entry")
    public record OrderStatusHistoryDto(
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String fromStatus,
            String toStatus,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String reason,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String changedBy,
            Instant changedAt) {}
}

package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirror of order-service's {@code OrderDto}.
 *
 * <p>The {@code statusHistory} field is present only when the request includes {@code
 * withHistory=true}. It's nullable here to reflect that.
 *
 * <p>All amount fields are structured as {@link MoneyDto} to match the service's serialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderClientDto(
        UUID id,
        String orderNumber,
        UUID customerId,
        String customerEmail,
        String status,
        MoneyDto subtotal,
        MoneyDto tax,
        MoneyDto shipping,
        MoneyDto total,
        ShippingAddressDto shippingAddress,
        List<OrderLineClientDto> lines,
        List<OrderStatusHistoryDto> statusHistory,
        Instant placedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoneyDto(BigDecimal amount, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShippingAddressDto(
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderStatusHistoryDto(
            String fromStatus,
            String toStatus,
            String reason,
            String changedBy,
            Instant changedAt) {}
}

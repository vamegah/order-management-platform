package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Filter for order listing")
public record OrderFilter(
        UUID customerId,
        String status,
        String orderNumber,
        Instant placedAfter,
        Instant placedBefore) {
    public OrderFilter {
        if (placedAfter != null && placedBefore != null && placedAfter.isAfter(placedBefore))
            throw new com.example.omp.order.error.InvalidOrderDataException(
                    "placedAfter must not exceed placedBefore");
    }

    public static OrderFilter empty() {
        return new OrderFilter(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return customerId == null
                && status == null
                && orderNumber == null
                && placedAfter == null
                && placedBefore == null;
    }
}

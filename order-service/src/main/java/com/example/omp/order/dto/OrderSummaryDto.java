package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A minimal order reference")
public record OrderSummaryDto(
        UUID id,
        String orderNumber,
        String status,
        BigDecimal total,
        String currency,
        Instant placedAt) {}

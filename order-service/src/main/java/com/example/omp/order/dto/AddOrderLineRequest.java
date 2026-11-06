package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(description = "Request to add a line to a DRAFT order")
public record AddOrderLineRequest(@NotNull UUID productId, @Positive int quantity) {}

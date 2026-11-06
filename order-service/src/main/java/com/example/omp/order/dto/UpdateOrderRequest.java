package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for updating a DRAFT order's header")
public record UpdateOrderRequest(@Size(max = 2000) String notes) {}

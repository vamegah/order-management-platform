package com.example.omp.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A minimal customer reference")
public record CustomerSummaryDto(
        @Schema(description = "Unique identifier") UUID id,
        @Schema(description = "Email address", example = "alice@omp.local") String email,
        @Schema(description = "Full name", example = "Alice Customer") String fullName) {}

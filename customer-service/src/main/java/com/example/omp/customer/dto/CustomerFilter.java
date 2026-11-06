package com.example.omp.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Filter for customer listing")
public record CustomerFilter(
        @Schema(description = "Filter by status", example = "ACTIVE") String status,
        @Schema(description = "Search by name (first OR last, case-insensitive)") String nameQuery,
        @Schema(description = "Filter by email domain (e.g., 'omp.local')") String emailDomain) {

    public static CustomerFilter empty() {
        return new CustomerFilter(null, null, null);
    }

    public boolean isEmpty() {
        return status == null && nameQuery == null && emailDomain == null;
    }
}

package com.example.omp.customer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A customer profile as returned by the API.
 *
 * <p>The full representation includes addresses and preferences. Summary representations (e.g., in
 * order details, Phase 3) omit them — see {@link CustomerSummaryDto}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A customer profile")
public record CustomerDto(
        @Schema(description = "Server-generated unique identifier") UUID id,
        @Schema(
                        description = "Email address (business identity, case-insensitive)",
                        example = "alice@omp.local")
                String email,
        @Schema(description = "Keycloak subject. Null until the customer links to a user.")
                String keycloakSub,
        @Schema(description = "Given name", example = "Alice") String firstName,
        @Schema(description = "Family name", example = "Customer") String lastName,
        @Schema(description = "Phone number, as provided", example = "+1-555-0101") String phone,
        @Schema(description = "Date of birth (null if not provided)", example = "1990-05-15")
                LocalDate dateOfBirth,
        @Schema(description = "Lifecycle status", example = "ACTIVE") String status,
        @Schema(description = "Addresses (billing, shipping). May be empty.")
                List<AddressDto> addresses,
        @Schema(description = "Preferences. Null if never set.") CustomerPreferencesDto preferences,
        @Schema(description = "Creation timestamp (UTC)") Instant createdAt,
        @Schema(description = "Last modification timestamp (UTC)") Instant updatedAt) {}

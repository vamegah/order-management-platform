package com.example.omp.gateway.clients.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating or updating an address")
public record CreateAddressRequest(
        @NotBlank
                @Pattern(
                        regexp = "^(BILLING|SHIPPING|BOTH)$",
                        message = "Must be one of BILLING, SHIPPING, BOTH")
                @Schema(description = "Address type", example = "SHIPPING")
                String addressType,
        @NotBlank
                @Size(max = 200)
                @Schema(description = "Primary address line", example = "200 Shipping Ave")
                String line1,
        @Size(max = 200) @Schema(description = "Secondary address line") String line2,
        @NotBlank @Size(max = 100) @Schema(description = "City", example = "Oakland") String city,
        @Size(max = 100) @Schema(description = "State/province", example = "CA") String state,
        @NotBlank @Size(max = 20) @Schema(description = "Postal code", example = "94607")
                String postalCode,
        @NotBlank
                @Pattern(
                        regexp = "^[A-Z]{2}$",
                        message = "Must be a 2-letter ISO 3166-1 country code")
                @Schema(description = "Country code", example = "US")
                String country,
        @Schema(description = "Set as default for its type") Boolean isDefault) {}

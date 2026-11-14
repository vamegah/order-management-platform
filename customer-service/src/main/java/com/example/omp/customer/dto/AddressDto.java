package com.example.omp.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A customer address")
public record AddressDto(
        @Schema(description = "Server-generated unique identifier") UUID id,
        @Schema(description = "Address type", example = "SHIPPING") String addressType,
        @Schema(description = "Primary address line", example = "200 Shipping Ave") String line1,
        @Schema(
                        description = "Secondary address line (optional)",
                        types = {"string", "null"})
                String line2,
        @Schema(description = "City", example = "Oakland") String city,
        @Schema(
                        description = "State/province (optional)",
                        types = {"string", "null"})
                String state,
        @Schema(description = "Postal code", example = "94607") String postalCode,
        @Schema(description = "ISO 3166-1 alpha-2 country code", example = "US") String country,
        @Schema(description = "True if this is the default address for its type")
                boolean isDefault) {}

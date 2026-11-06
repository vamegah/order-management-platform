package com.example.omp.gateway.clients.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Request body for creating a customer")
public record CreateCustomerRequest(
        @NotBlank
                @Email
                @Size(max = 254)
                @Schema(
                        description = "Email address (will be normalized to lowercase)",
                        example = "newcustomer@example.com")
                String email,
        @NotBlank @Size(max = 100) @Schema(description = "Given name", example = "New")
                String firstName,
        @NotBlank @Size(max = 100) @Schema(description = "Family name", example = "Customer")
                String lastName,
        @Size(max = 40) @Schema(description = "Phone number (optional)", example = "+1-555-0199")
                String phone,
        @Past
                @Schema(
                        description = "Date of birth (optional, must be in the past)",
                        example = "1990-01-15")
                LocalDate dateOfBirth,
        @Schema(description = "Initial addresses (optional)")
                List<@Valid CreateAddressRequest> addresses,
        @Valid @Schema(description = "Initial preferences (optional)")
                CreatePreferencesRequest preferences) {

    @Schema(description = "Initial preferences for a new customer")
    public record CreatePreferencesRequest(
            @Size(max = 20) @Schema(description = "BCP 47 language tag", example = "en-US")
                    String locale,
            @Size(min = 3, max = 3) @Schema(description = "ISO 4217 currency code", example = "USD")
                    String currency,
            @Schema(description = "Marketing opt-in") Boolean marketingOptIn,
            @Schema(description = "Email notifications") Boolean notificationEmail,
            @Schema(description = "SMS notifications") Boolean notificationSms) {}
}

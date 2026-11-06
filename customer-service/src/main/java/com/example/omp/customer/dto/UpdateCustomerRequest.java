package com.example.omp.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request body for updating a customer's mutable fields.
 *
 * <p>Email is mutable (used for account recovery). Keycloak {@code sub} is managed separately via
 * the link/unlink endpoints.
 */
@Schema(description = "Request body for updating a customer")
public record UpdateCustomerRequest(
        @NotBlank @Email @Size(max = 254) @Schema(description = "Email address") String email,
        @NotBlank @Size(max = 100) @Schema(description = "Given name") String firstName,
        @NotBlank @Size(max = 100) @Schema(description = "Family name") String lastName,
        @Size(max = 40) @Schema(description = "Phone number") String phone,
        @Past @Schema(description = "Date of birth") LocalDate dateOfBirth) {}

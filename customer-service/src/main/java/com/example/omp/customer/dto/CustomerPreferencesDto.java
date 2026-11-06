package com.example.omp.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Customer preferences")
public record CustomerPreferencesDto(
        @Schema(description = "BCP 47 language tag", example = "en-US") String locale,
        @Schema(description = "ISO 4217 currency code", example = "USD") String currency,
        @Schema(description = "Marketing communications opt-in") boolean marketingOptIn,
        @Schema(description = "Email notifications enabled") boolean notificationEmail,
        @Schema(description = "SMS notifications enabled") boolean notificationSms) {}

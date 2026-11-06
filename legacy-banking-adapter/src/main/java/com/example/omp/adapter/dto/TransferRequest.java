package com.example.omp.adapter.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank @Pattern(regexp = "ACC-\\d+", message = "must match ACC-<number>")
                String fromAccount,
        @NotBlank @Pattern(regexp = "ACC-\\d+", message = "must match ACC-<number>")
                String toAccount,
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "USD|EUR|GBP", message = "must be a supported currency")
                String currency,
        @NotBlank String reference) {}

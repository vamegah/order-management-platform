package com.example.omp.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(
        description =
                "Set supplied preferences, preserving omitted/null fields. New preference records use en-US, USD, email on, marketing/SMS off.")
public record UpdatePreferencesRequest(
        @Size(max = 20)
                @Pattern(regexp = ".*\\S.*", message = "must not be blank")
                @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                String locale,
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter currency")
                @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                String currency,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"boolean", "null"})
                Boolean marketingOptIn,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"boolean", "null"})
                Boolean notificationEmail,
        @io.swagger.v3.oas.annotations.media.Schema(types = {"boolean", "null"})
                Boolean notificationSms) {}

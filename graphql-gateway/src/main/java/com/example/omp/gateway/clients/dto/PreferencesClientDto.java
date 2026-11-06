package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mirror of customer-service's {@code CustomerPreferencesDto}.
 *
 * <p>Used as both a request body (for {@code PUT /customers/{id}/preferences}) and a response type.
 * Jackson serializes and deserializes the same shape, so one record serves both roles.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreferencesClientDto(
        String locale,
        String currency,
        boolean marketingOptIn,
        boolean notificationEmail,
        boolean notificationSms) {}

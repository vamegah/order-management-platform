package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Mirror of customer-service's {@code AddressDto}.
 *
 * <p>Only the fields the gateway consumes. Additional fields returned by the downstream service are
 * ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressClientDto(
        UUID id,
        String addressType,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        boolean isDefault) {}

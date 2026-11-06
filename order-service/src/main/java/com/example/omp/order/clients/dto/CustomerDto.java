package com.example.omp.order.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String status,
        List<AddressDto> addresses,
        PreferencesDto preferences) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressDto(
            UUID id,
            String addressType,
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country,
            boolean isDefault) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreferencesDto(String locale, String currency) {}
}

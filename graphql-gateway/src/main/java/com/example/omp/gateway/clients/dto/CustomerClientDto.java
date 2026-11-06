package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerClientDto(
        UUID id,
        String email,
        String keycloakSub,
        String firstName,
        String lastName,
        String phone,
        LocalDate dateOfBirth,
        String status,
        List<AddressClientDto> addresses,
        PreferencesClientDto preferences,
        Instant createdAt,
        Instant updatedAt) {}

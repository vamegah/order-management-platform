package com.example.omp.notification.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerDto(
        UUID id,
        String email,
        String keycloakSub,
        String firstName,
        String lastName,
        String status) {}

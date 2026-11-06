package com.example.omp.notification.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDto(UUID id, String orderNumber, UUID customerId, String status) {}

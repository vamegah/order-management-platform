package com.example.omp.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoveryAdoptionRequest(@NotBlank @Size(max = 500) String reason) {}

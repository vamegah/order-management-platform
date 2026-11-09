package com.example.omp.order.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record TerminalReservationDispositionRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 2000) String evidence,
        @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> lineIds) {}

package com.example.omp.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record ReservationReferenceRepairRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 500) String reason,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Binding> bindings) {
    public record Binding(@NotNull UUID lineId, @NotNull UUID reservationId) {}
}

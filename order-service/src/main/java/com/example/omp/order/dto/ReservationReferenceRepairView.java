package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record ReservationReferenceRepairView(
        UUID orderId, long version, String status, int missing, int restored, List<Line> lines) {
    public record Line(
            UUID lineId,
            String sku,
            int quantity,
            UUID reservationId,
            @Schema(
                            nullable = true,
                            maxLength = 64,
                            accessMode = Schema.AccessMode.READ_ONLY,
                            description =
                                    "Original verified checkout warehouse; null when historical provenance is unknown")
                    String reservationWarehouseId) {}
}

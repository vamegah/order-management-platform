package com.example.omp.order.dto;

import java.util.List;
import java.util.UUID;

public record ReservationReferenceRepairView(
        UUID orderId, long version, String status, int missing, int restored, List<Line> lines) {
    public record Line(UUID lineId, String sku, int quantity, UUID reservationId) {}
}

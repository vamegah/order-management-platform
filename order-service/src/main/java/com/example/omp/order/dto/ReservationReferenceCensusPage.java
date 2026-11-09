package com.example.omp.order.dto;

import java.util.List;
import java.util.UUID;

public record ReservationReferenceCensusPage(
        int scanned, UUID nextAfterLineId, List<Finding> findings) {
    public enum Issue {
        MISSING_REFERENCE,
        SHARED_REFERENCE
    }

    public record Finding(
            UUID lineId,
            UUID orderId,
            long orderVersion,
            String status,
            String sku,
            int quantity,
            UUID reservationId,
            Issue issue) {}
}

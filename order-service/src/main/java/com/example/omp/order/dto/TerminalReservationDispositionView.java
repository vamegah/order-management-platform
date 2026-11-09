package com.example.omp.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TerminalReservationDispositionView(
        UUID orderId, long version, String status, int recorded, List<Line> lines) {
    @Schema(name = "TerminalReservationDispositionLine")
    public record Line(
            UUID lineId,
            String sku,
            int quantity,
            UUID reservationId,
            boolean eligible,
            Audit audit) {}

    @Schema(name = "TerminalReservationDispositionAudit")
    public record Audit(
            UUID id,
            String policy,
            long previousOrderVersion,
            String actor,
            String reason,
            String evidence,
            Instant recordedAt) {}
}

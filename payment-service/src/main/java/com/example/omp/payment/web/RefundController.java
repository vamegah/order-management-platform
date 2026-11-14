package com.example.omp.payment.web;

import com.example.omp.payment.domain.Refund;
import com.example.omp.payment.service.RefundApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/refunds")
public class RefundController {

    private final RefundApplicationService refundService;

    public RefundController(RefundApplicationService refundService) {
        this.refundService = refundService;
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Refund completed",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "Refund pending; retry with the same caller key",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "Refund failed",
            useReturnTypeSchema = true)
    @org.springframework.security.access.prepost.PreAuthorize(
            "@paymentAccess.canAccess(#paymentId, authentication)")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable UUID paymentId, @Valid @RequestBody RefundHttpRequest body) {

        Refund refund =
                refundService.refund(
                        paymentId, body.amountMinor(), body.reason(), body.idempotencyKey());

        HttpStatus status =
                switch (refund.getStatus()) {
                    case COMPLETED -> HttpStatus.OK;
                    case FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case PENDING -> HttpStatus.ACCEPTED;
                };
        return ResponseEntity.status(status).body(RefundResponse.from(refund));
    }

    public record RefundHttpRequest(
            @Min(1) long amountMinor, String reason, @NotBlank String idempotencyKey) {}

    public record RefundResponse(
            UUID id,
            UUID paymentId,
            long amountMinor,
            String currency,
            String status,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String providerReference,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String reason,
            String createdAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String completedAt) {
        static RefundResponse from(Refund r) {
            return new RefundResponse(
                    r.getId(),
                    r.getPaymentId(),
                    r.getAmountMinor(),
                    r.getCurrency(),
                    r.getStatus().name(),
                    r.getProviderReference(),
                    r.getReason(),
                    str(r.getCreatedAt()),
                    str(r.getCompletedAt()));
        }

        private static String str(java.time.Instant i) {
            return i == null ? null : i.toString();
        }
    }
}

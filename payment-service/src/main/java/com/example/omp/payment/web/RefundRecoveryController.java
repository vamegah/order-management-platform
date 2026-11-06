package com.example.omp.payment.web;

import com.example.omp.payment.domain.Refund;
import com.example.omp.payment.service.RefundRecoveryService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/refunds/{refundId}")
public class RefundRecoveryController {
    private final RefundRecoveryService recovery;

    public RefundRecoveryController(RefundRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping
    public RecoveryResponse inspect(@PathVariable UUID paymentId, @PathVariable UUID refundId) {
        return RecoveryResponse.from(recovery.inspect(paymentId, refundId));
    }

    @PostMapping("/retry")
    public ResponseEntity<RecoveryResponse> retry(
            @PathVariable UUID paymentId, @PathVariable UUID refundId) {
        Refund refund = recovery.retry(paymentId, refundId);
        var response = RecoveryResponse.from(refund);
        return switch (refund.getStatus()) {
            case COMPLETED -> ResponseEntity.ok(response);
            case FAILED -> ResponseEntity.unprocessableEntity().body(response);
            case PENDING -> ResponseEntity.accepted().header("Retry-After", "60").body(response);
        };
    }

    public record RecoveryResponse(
            RefundController.RefundResponse refund,
            int attempts,
            @com.fasterxml.jackson.annotation.JsonFormat(
                            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
                    Instant nextAttemptAt,
            String lastError,
            String attentionReason) {
        static RecoveryResponse from(Refund refund) {
            return new RecoveryResponse(
                    RefundController.RefundResponse.from(refund),
                    refund.getRecoveryAttempts(),
                    refund.getNextAttemptAt(),
                    refund.getLastError(),
                    refund.getAttentionReason());
        }
    }
}

package com.example.omp.payment.web;

import com.example.omp.payment.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/refunds/{refundId}/reconciliation")
public class RefundReconciliationController {
    private final RefundReconciliationService service;

    public RefundReconciliationController(RefundReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Response> reconcile(
            @PathVariable UUID paymentId,
            @PathVariable UUID refundId,
            @Valid @RequestBody Request body) {
        var result =
                service.reconcile(paymentId, refundId, body.providerReference(), body.reason());
        var response =
                new Response(
                        RefundRecoveryController.RecoveryResponse.from(result.refund()),
                        result.audit());
        if (result.audit().outcome().equals("BLOCKED"))
            return ResponseEntity.status(409).body(response);
        return switch (result.refund().getStatus()) {
            case COMPLETED -> ResponseEntity.ok(response);
            case FAILED -> ResponseEntity.unprocessableEntity().body(response);
            case PENDING -> ResponseEntity.accepted().header("Retry-After", "60").body(response);
        };
    }

    @GetMapping
    public List<RefundReconciliationStore.AuditView> history(
            @PathVariable UUID paymentId, @PathVariable UUID refundId) {
        return service.history(paymentId, refundId);
    }

    public record Request(
            @NotBlank @Pattern(regexp = "re_[A-Za-z0-9_]{1,240}") String providerReference,
            @NotBlank @Size(max = 500) String reason) {}

    public record Response(
            RefundRecoveryController.RecoveryResponse recovery,
            RefundReconciliationStore.AuditView audit) {}
}

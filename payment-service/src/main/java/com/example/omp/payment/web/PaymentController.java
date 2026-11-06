package com.example.omp.payment.web;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.service.PaymentApplicationService;
import com.example.omp.payment.service.PaymentApplicationService.ChargeCommand;
import com.example.omp.payment.service.PaymentApplicationService.ChargeOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentApplicationService paymentService;

    public PaymentController(PaymentApplicationService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<ChargeResponse> charge(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChargeHttpRequest body) {
        String claim = jwt.getClaimAsString("customer_id");
        if (claim == null)
            throw new org.springframework.security.access.AccessDeniedException(
                    "Customer identity required");
        UUID customerId = UUID.fromString(claim);

        ChargeCommand command =
                new ChargeCommand(
                        body.orderId(),
                        customerId,
                        body.amountMinor(),
                        body.currency(),
                        body.idempotencyKey());

        ChargeOutcome outcome = paymentService.charge(command);

        HttpStatus status =
                switch (outcome.result()) {
                    case "confirmed" -> HttpStatus.OK;
                    case "declined" -> HttpStatus.PAYMENT_REQUIRED;
                    case "requires_action" -> HttpStatus.ACCEPTED;
                    case "error" -> HttpStatus.BAD_GATEWAY;
                    default -> HttpStatus.OK;
                };

        return ResponseEntity.status(status).body(ChargeResponse.from(outcome));
    }

    @GetMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize(
            "@paymentAccess.canAccess(#id, authentication)")
    public PaymentResponse get(@PathVariable UUID id) {
        Payment p = paymentService.get(id);
        return PaymentResponse.from(p);
    }

    // --- HTTP DTOs ---

    public record ChargeHttpRequest(
            @NotNull UUID orderId,
            @Min(1) long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @NotBlank String idempotencyKey) {}

    public record ChargeResponse(
            String result,
            UUID paymentId,
            String providerReference,
            String failureCode,
            String failureMessage,
            String requiresActionUrl) {
        static ChargeResponse from(ChargeOutcome o) {
            return new ChargeResponse(
                    o.result(),
                    o.paymentId(),
                    o.providerReference(),
                    o.failureCode(),
                    o.failureMessage(),
                    o.requiresActionUrl());
        }
    }

    public record PaymentResponse(
            UUID id,
            UUID orderId,
            UUID customerId,
            long amountMinor,
            String currency,
            String status,
            String provider,
            String providerReference,
            String failureCode,
            String failureMessage,
            String createdAt,
            String capturedAt,
            String failedAt,
            String refundedAt) {
        static PaymentResponse from(Payment p) {
            return new PaymentResponse(
                    p.getId(),
                    p.getOrderId(),
                    p.getCustomerId(),
                    p.getAmountMinor(),
                    p.getCurrency(),
                    p.getStatus().name(),
                    p.getProvider(),
                    p.getProviderReference(),
                    p.getFailureCode(),
                    p.getFailureMessage(),
                    str(p.getCreatedAt()),
                    str(p.getCapturedAt()),
                    str(p.getFailedAt()),
                    str(p.getRefundedAt()));
        }

        private static String str(java.time.Instant i) {
            return i == null ? null : i.toString();
        }
    }
}

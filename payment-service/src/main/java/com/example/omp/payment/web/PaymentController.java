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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Charge confirmed",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "Customer action required",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "402",
            description = "Charge declined",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "Provider outcome unavailable",
            useReturnTypeSchema = true)
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
                        body.idempotencyKey(),
                        body.paymentMethodId());

        ChargeOutcome outcome = paymentService.charge(command);

        HttpStatus status =
                switch (outcome.result()) {
                    case "confirmed" -> HttpStatus.OK;
                    case "declined" -> HttpStatus.PAYMENT_REQUIRED;
                    case "requires_action", "pending" -> HttpStatus.ACCEPTED;
                    case "error" -> HttpStatus.BAD_GATEWAY;
                    default -> HttpStatus.OK;
                };

        return ResponseEntity.status(status)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ChargeResponse.from(outcome));
    }

    @PostMapping("/{id}/confirm")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Existing or newly confirmed charge",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "Provider processing or customer action required",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "402",
            description = "Charge declined",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "Provider outcome unresolved",
            useReturnTypeSchema = true)
    @org.springframework.security.access.prepost.PreAuthorize(
            "@paymentAccess.canAccess(#id, authentication)")
    public ResponseEntity<ChargeResponse> confirm(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConfirmHttpRequest request) {
        String customer = jwt.getClaimAsString("customer_id");
        if (customer == null)
            throw new org.springframework.security.access.AccessDeniedException(
                    "Customer identity required");
        ChargeOutcome outcome =
                paymentService.confirm(id, UUID.fromString(customer), request.paymentMethodId());
        int status =
                switch (outcome.result()) {
                    case "requires_action", "pending" -> 202;
                    case "error" -> 502;
                    case "declined" -> 402;
                    default -> 200;
                };
        return ResponseEntity.status(status)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ChargeResponse.from(outcome));
    }

    public record ConfirmHttpRequest(
            @NotBlank @Pattern(regexp = "pm_[A-Za-z0-9_]{1,240}") String paymentMethodId) {}

    public record NextAction(String type, String clientSecret) {
        @Override
        public String toString() {
            return "NextAction[type=" + type + "]";
        }
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
            @NotBlank String idempotencyKey,
            @Pattern(regexp = "pm_[A-Za-z0-9_]{1,240}") String paymentMethodId) {
        public ChargeHttpRequest(UUID orderId, long amountMinor, String currency, String key) {
            this(orderId, amountMinor, currency, key, null);
        }
    }

    public record ChargeResponse(
            String result,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) UUID paymentId,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String providerReference,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String failureCode,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String failureMessage,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String requiresActionUrl,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    NextAction nextAction) {
        static ChargeResponse from(ChargeOutcome o) {
            return new ChargeResponse(
                    o.result(),
                    o.paymentId(),
                    o.providerReference(),
                    o.failureCode(),
                    o.failureMessage(),
                    o.requiresActionUrl(),
                    o.clientSecret() == null
                            ? null
                            : new NextAction("use_stripe_sdk", o.clientSecret()));
        }
    }

    public record PaymentResponse(
            UUID id,
            UUID orderId,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) UUID customerId,
            long amountMinor,
            String currency,
            String status,
            String provider,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String providerReference,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String failureCode,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String failureMessage,
            String createdAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
                    String capturedAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String failedAt,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
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

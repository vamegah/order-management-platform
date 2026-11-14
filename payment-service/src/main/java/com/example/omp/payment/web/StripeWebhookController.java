package com.example.omp.payment.web;

import com.example.omp.payment.metrics.PaymentMetrics;
import com.example.omp.payment.provider.PaymentProvider;
import com.example.omp.payment.provider.ProviderWebhookVerification;
import com.example.omp.payment.service.PaymentProviderEventService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives asynchronous webhooks from the payment provider.
 *
 * <p>This endpoint is NOT JWT-protected (the provider has no JWT). It is authenticated by the
 * provider's HMAC signature instead.
 *
 * <p>Contract: - read the raw body (byte-for-byte; do not re-serialize) - verify the provider's
 * signature - persist the event for idempotency - apply state changes + write outbox events -
 * respond 200 quickly; the provider retries on non-2xx
 */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "providerSignature")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "noopSignature")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentProvider provider;
    private final PaymentProviderEventService eventService;
    private final PaymentMetrics metrics;

    public StripeWebhookController(
            PaymentProvider provider,
            PaymentProviderEventService eventService,
            PaymentMetrics metrics) {
        this.provider = provider;
        this.eventService = eventService;
        this.metrics = metrics;
    }

    /**
     * Path includes the provider name so we can route to the right verifier when more than one
     * provider is supported. For Phase 5 only "stripe" and "noop" exist; the mapping is dynamic.
     */
    @PostMapping("/{providerName}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Verified event accepted, duplicated or ignored",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Provider signature rejected",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Unknown provider",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Event processing failed; provider should retry",
            useReturnTypeSchema = true)
    public ResponseEntity<Map<String, String>> receive(
            @PathVariable String providerName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description =
                                    "Original provider JSON bytes. The signature is verified before processing; clients must not reserialize signed payloads.",
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @io.swagger.v3.oas.annotations.media.Schema(
                                                            type = "object")))
                    @RequestBody
                    byte[] rawBody,
            HttpServletRequest request) {

        if (!provider.providerName().equalsIgnoreCase(providerName)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Collections.singletonMap("error", "unknown provider"));
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        Map<String, String> headers = extractHeaders(request);

        ProviderWebhookVerification verification = provider.verifyWebhook(body, headers);

        if (!verification.valid()) {
            log.warn("Webhook signature verification failed: {}", verification.reason());
            metrics.recordWebhookSignatureFailure();
            metrics.recordWebhook(providerName, "invalid_signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("error", verification.reason()));
        }

        try {
            PaymentProviderEventService.ProcessResult result =
                    eventService.process(providerName, verification.event());

            String outcome =
                    switch (result) {
                        case ACCEPTED -> "accepted";
                        case DUPLICATE -> "duplicate";
                        case IGNORED -> "ignored";
                    };
            metrics.recordWebhook(providerName, outcome);

            return ResponseEntity.ok(Map.of("status", outcome));
        } catch (RuntimeException e) {
            log.error("Error processing PSP webhook", e);
            metrics.recordWebhook(providerName, "error");
            // 500 -> the provider retries; we've already stored the event.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "processing_failed"));
        }
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}

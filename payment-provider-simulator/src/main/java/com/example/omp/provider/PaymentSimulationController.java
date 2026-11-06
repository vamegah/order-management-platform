package com.example.omp.provider;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints that trigger simulated payment flows.
 *
 * <p>These endpoints are for development and testing. In production, the simulator is not deployed;
 * real payments originate from the customer's browser hitting a real provider's payment page.
 *
 * <p>Each endpoint:
 *
 * <ol>
 *   <li>Validates the request.
 *   <li>Generates a webhook event ID and (if not provided) a payment intent ID.
 *   <li>Schedules a webhook send after the configured delay.
 *   <li>Returns immediately with the event ID.
 * </ol>
 *
 * <p>The delayed send simulates the round-trip time a real provider would take to process a
 * payment. It also means the caller's HTTP request completes quickly, matching the real provider's
 * behavior (the browser gets a redirect to a "processing" page while the provider works in the
 * background).
 *
 * <p><b>Why schedule on a separate thread?</b> Because the caller (a test, a dev tool, or the
 * browser) shouldn't wait for the webhook to be sent. The webhook send is fire-and-forget from the
 * caller's perspective.
 */
@RestController
@RequestMapping("/api/simulate")
@Tag(name = "Payment Simulation", description = "Trigger simulated payment flows (dev/test only)")
public class PaymentSimulationController {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulationController.class);

    private final WebhookSender webhookSender;
    private final SimulatorProperties properties;
    private final ThreadPoolTaskScheduler scheduler;
    private final MeterRegistry metrics;

    public PaymentSimulationController(
            WebhookSender webhookSender,
            SimulatorProperties properties,
            @Qualifier("webhookScheduler") ThreadPoolTaskScheduler scheduler,
            MeterRegistry metrics) {
        this.webhookSender = webhookSender;
        this.properties = properties;
        this.scheduler = scheduler;
        this.metrics = metrics;
    }

    /**
     * Simulates a successful payment. After the configured delay, sends a {@code
     * payment_intent.succeeded} webhook to the callback URL.
     */
    @PostMapping("/payment/success")
    @Operation(
            summary = "Simulate a successful payment",
            description =
                    "Schedules a payment_intent.succeeded webhook to be "
                            + "sent after the configured delay. Returns the event ID "
                            + "immediately.")
    public ResponseEntity<SimulationResponse> simulateSuccess(
            @Valid @RequestBody PaymentSimulationRequest request) {

        String eventId = "evt_" + UUID.randomUUID();
        String paymentIntentId =
                request.paymentIntentId() != null
                        ? request.paymentIntentId()
                        : "pi_" + UUID.randomUUID();

        WebhookEventPayload payload =
                WebhookEventPayload.succeeded(
                        eventId, paymentIntentId, request.amount(), request.currency());

        scheduleWebhook(payload);

        log.info(
                "Scheduled success webhook eventId={} paymentIntentId={} amount={} {}",
                eventId,
                paymentIntentId,
                request.amount(),
                request.currency());
        metrics.counter("simulator.payments.initiated", "outcome", "success").increment();

        return ResponseEntity.accepted()
                .body(
                        new SimulationResponse(
                                eventId,
                                paymentIntentId,
                                "Scheduled in " + properties.getWebhookDelayMs() + "ms"));
    }

    /**
     * Simulates a failed payment. After the configured delay, sends a {@code
     * payment_intent.payment_failed} webhook to the callback URL.
     */
    @PostMapping("/payment/failure")
    @Operation(
            summary = "Simulate a failed payment",
            description =
                    "Schedules a payment_intent.payment_failed webhook "
                            + "to be sent after the configured delay. Returns the "
                            + "event ID immediately.")
    public ResponseEntity<SimulationResponse> simulateFailure(
            @Valid @RequestBody PaymentSimulationRequest request) {

        String eventId = "evt_" + UUID.randomUUID();
        String paymentIntentId =
                request.paymentIntentId() != null
                        ? request.paymentIntentId()
                        : "pi_" + UUID.randomUUID();

        String reason = request.failureReason() != null ? request.failureReason() : "card_declined";

        WebhookEventPayload payload =
                WebhookEventPayload.failed(
                        eventId, paymentIntentId, request.amount(), request.currency(), reason);

        scheduleWebhook(payload);

        log.info(
                "Scheduled failure webhook eventId={} paymentIntentId={} reason={}",
                eventId,
                paymentIntentId,
                reason);
        metrics.counter("simulator.payments.initiated", "outcome", "failure").increment();

        return ResponseEntity.accepted()
                .body(
                        new SimulationResponse(
                                eventId,
                                paymentIntentId,
                                "Scheduled in " + properties.getWebhookDelayMs() + "ms"));
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Schedules the webhook send after the configured delay.
     *
     * <p>The scheduler runs the send on a background thread, so the caller (this controller)
     * returns immediately.
     */
    private void scheduleWebhook(WebhookEventPayload payload) {
        long delayMs = properties.getWebhookDelayMs();
        Instant sendAt = Instant.now().plusMillis(delayMs);

        scheduler.schedule(() -> webhookSender.send(payload), sendAt);
    }

    // =========================================================================
    // Request and response DTOs
    // =========================================================================

    /** Request body for the simulation endpoints. */
    @Schema(description = "Request to simulate a payment")
    public record PaymentSimulationRequest(
            @NotNull @Positive @Schema(description = "Payment amount", example = "149.99")
                    BigDecimal amount,
            @NotBlank @Schema(description = "ISO 4217 currency code", example = "USD")
                    String currency,
            @Schema(
                            description =
                                    "Optional payment intent ID. If omitted, "
                                            + "a new one is generated.",
                            example = "pi_1234567890")
                    String paymentIntentId,
            @Schema(
                            description =
                                    "For failure simulations, the reason "
                                            + "(e.g., card_declined, insufficient_funds). "
                                            + "Ignored for success simulations.",
                            example = "card_declined")
                    String failureReason) {}

    /** Response body for the simulation endpoints. */
    @Schema(description = "Acknowledgement of a scheduled simulation")
    public record SimulationResponse(
            @Schema(description = "The webhook event ID that will be sent") String eventId,
            @Schema(description = "The payment intent ID this event refers to")
                    String paymentIntentId,
            @Schema(description = "Human-readable status message") String message) {}
}

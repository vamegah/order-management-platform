package com.example.omp.notification.webhook;

import com.example.omp.notification.webhook.dto.DeliveryAttemptResponse;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhook-deliveries")
public class WebhookDeliveryController {

    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookSubscriptionService subscriptionService;
    private final SubscriptionOwnerResolver ownerResolver;
    private final com.example.omp.notification.webhook.dlq.DlqService dlqService;

    public WebhookDeliveryController(
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookSubscriptionService subscriptionService,
            SubscriptionOwnerResolver ownerResolver,
            com.example.omp.notification.webhook.dlq.DlqService dlqService) {
        this.attemptRepository = attemptRepository;
        this.subscriptionService = subscriptionService;
        this.ownerResolver = ownerResolver;
        this.dlqService = dlqService;
    }

    /**
     * Delivery history for a subscription. Owner-scoped: a customer may only query their own
     * subscriptions; admins may query any.
     */
    @GetMapping
    public List<DeliveryAttemptResponse> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID subscriptionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) int size) {

        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        subscriptionService.get(subscriptionId, owner.type(), owner.id()); // ownership check

        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<WebhookDeliveryAttempt> attempts =
                attemptRepository.findBySubscriptionIdOrderByScheduledAtDesc(
                        subscriptionId, pageable);

        return attempts.stream().map(DeliveryAttemptResponse::from).toList();
    }

    /**
     * Queue a replay through the DLQ authority. Administrator access is enforced by both the HTTP
     * matcher and method security; completion remains asynchronous.
     */
    @PostMapping("/{deliveryId}/replay")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "Replay queued",
            useReturnTypeSchema = true)
    public org.springframework.http.ResponseEntity<ReplayResponse> replay(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID deliveryId) {
        dlqService.replay(deliveryId, jwt.getSubject());
        return org.springframework.http.ResponseEntity.accepted()
                .body(new ReplayResponse(deliveryId, "queued"));
    }

    public record ReplayResponse(UUID deliveryId, String status) {}
}

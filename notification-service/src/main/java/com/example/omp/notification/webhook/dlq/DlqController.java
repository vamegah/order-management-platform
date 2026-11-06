package com.example.omp.notification.webhook.dlq;

import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only DLQ endpoints. Authorization is enforced in WebhookSecurityConfig (Response 4): all
 * /api/v1/webhook-dlq/** requires ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/webhook-dlq")
public class DlqController {

    private final DlqService dlqService;
    private final WebhookDlqRepository repository;
    private final WebhookSubscriptionRepository subscriptionRepository;

    public DlqController(
            DlqService dlqService,
            WebhookDlqRepository repository,
            WebhookSubscriptionRepository subscriptionRepository) {
        this.dlqService = dlqService;
        this.repository = repository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @GetMapping
    public Page<DlqEntryResponse> list(
            @RequestParam(defaultValue = "false") boolean includeReplayed,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<WebhookDlqEntry> entries =
                includeReplayed
                        ? repository.findAllByOrderByReceivedAtDesc(pageable)
                        : repository.findByReplayedAtIsNullOrderByReceivedAtDesc(pageable);

        return entries.map(this::toResponse);
    }

    @PostMapping("/{deliveryId}/replay")
    public DlqEntryResponse replay(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID deliveryId) {
        String actor = jwt.getSubject();
        dlqService.replay(deliveryId, actor);
        WebhookDlqEntry entry =
                repository
                        .findByDeliveryId(deliveryId)
                        .orElseThrow(() -> new IllegalStateException("DLQ entry vanished"));
        return toResponse(entry);
    }

    @DeleteMapping("/{deliveryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID deliveryId) {
        repository.findByDeliveryId(deliveryId).ifPresent(repository::delete);
    }

    private DlqEntryResponse toResponse(WebhookDlqEntry entry) {
        String subscriptionName =
                subscriptionRepository
                        .findById(entry.getSubscriptionId())
                        .map(WebhookSubscription::getName)
                        .orElse(null);
        return new DlqEntryResponse(
                entry.getId(),
                entry.getDeliveryId(),
                entry.getSubscriptionId(),
                subscriptionName,
                entry.getEventId(),
                entry.getEventType(),
                entry.getReason(),
                entry.getFinalStatus(),
                entry.getAttempts(),
                entry.getReceivedAt(),
                entry.getReplayedAt(),
                entry.getReplayedBy());
    }
}

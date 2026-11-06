package com.example.omp.notification.webhook;

import com.example.omp.notification.webhook.delivery.WebhookTestPublisher;
import com.example.omp.notification.webhook.dto.CreateSubscriptionRequest;
import com.example.omp.notification.webhook.dto.SubscriptionResponse;
import com.example.omp.notification.webhook.dto.SubscriptionSecretResponse;
import com.example.omp.notification.webhook.dto.UpdateSubscriptionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhook-subscriptions")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;
    private final SubscriptionOwnerResolver ownerResolver;
    private final WebhookTestPublisher testPublisher;

    public WebhookSubscriptionController(
            WebhookSubscriptionService service,
            SubscriptionOwnerResolver ownerResolver,
            WebhookTestPublisher testPublisher) {
        this.service = service;
        this.ownerResolver = ownerResolver;
        this.testPublisher = testPublisher;
    }

    @PostMapping
    public ResponseEntity<SubscriptionSecretResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateSubscriptionRequest request) {

        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        WebhookSubscription saved = service.createEntity(owner.type(), owner.id(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SubscriptionSecretResponse(saved.getId(), saved.getSecret()));
    }

    @GetMapping
    public List<SubscriptionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        return service.list(owner.type(), owner.id());
    }

    @GetMapping("/{id}")
    public SubscriptionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        return service.get(id, owner.type(), owner.id());
    }

    @PutMapping("/{id}")
    public SubscriptionResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        return service.update(id, owner.type(), owner.id(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        service.delete(id, owner.type(), owner.id());
    }

    @PostMapping("/{id}/rotate-secret")
    public SubscriptionSecretResponse rotateSecret(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        String secret = service.rotateSecret(id, owner.type(), owner.id());
        return new SubscriptionSecretResponse(id, secret);
    }

    @PostMapping("/{id}/enable")
    public SubscriptionResponse enable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        return service.enable(id, owner.type(), owner.id());
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, String>> sendTest(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {

        SubscriptionOwner owner = ownerResolver.resolve(jwt);
        WebhookSubscription sub = service.loadOwned(id, owner.type(), owner.id());
        UUID deliveryId = testPublisher.publishTest(sub);
        return ResponseEntity.accepted()
                .body(Map.of("deliveryId", deliveryId.toString(), "status", "queued"));
    }
}

package com.example.omp.notification.webhook;

import com.example.omp.notification.webhook.dto.CreateSubscriptionRequest;
import com.example.omp.notification.webhook.dto.SubscriptionResponse;
import com.example.omp.notification.webhook.dto.UpdateSubscriptionRequest;
import com.example.omp.notification.webhook.error.WebhookExceptions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookSubscriptionService {

    private final WebhookSubscriptionRepository repository;
    private final SecretGenerator secretGenerator;

    public WebhookSubscriptionService(
            WebhookSubscriptionRepository repository, SecretGenerator secretGenerator) {
        this.repository = repository;
        this.secretGenerator = secretGenerator;
    }

    @Transactional
    public SubscriptionResponse create(
            SubscriptionOwnerType ownerType, UUID ownerId, CreateSubscriptionRequest request) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(ownerType);
        sub.setOwnerId(ownerId);
        sub.setName(request.name());
        sub.setTargetUrl(request.targetUrl());
        sub.setEventTypes(List.copyOf(request.eventTypes()));
        sub.setSecret(secretGenerator.generate());
        sub.setEnabled(true);
        WebhookSubscription saved = repository.save(sub);
        return SubscriptionResponse.from(saved);
    }

    /** Returns the raw secret. This is the only method that exposes it. */
    @Transactional
    public String createAndReturnSecret(
            SubscriptionOwnerType ownerType, UUID ownerId, CreateSubscriptionRequest request) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(ownerType);
        sub.setOwnerId(ownerId);
        sub.setName(request.name());
        sub.setTargetUrl(request.targetUrl());
        sub.setEventTypes(List.copyOf(request.eventTypes()));
        sub.setSecret(secretGenerator.generate());
        sub.setEnabled(true);
        return repository.save(sub).getSecret();
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse get(UUID id, SubscriptionOwnerType ownerType, UUID ownerId) {
        WebhookSubscription sub = loadOwned(id, ownerType, ownerId);
        return SubscriptionResponse.from(sub);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> list(SubscriptionOwnerType ownerType, UUID ownerId) {
        return repository.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(ownerType, ownerId).stream()
                .map(SubscriptionResponse::from)
                .toList();
    }

    @Transactional
    public SubscriptionResponse update(
            UUID id,
            SubscriptionOwnerType ownerType,
            UUID ownerId,
            UpdateSubscriptionRequest request) {
        WebhookSubscription sub = loadOwned(id, ownerType, ownerId);

        if (request.name() != null) {
            sub.setName(request.name());
        }
        if (request.targetUrl() != null) {
            sub.setTargetUrl(request.targetUrl());
        }
        if (request.eventTypes() != null && !request.eventTypes().isEmpty()) {
            sub.setEventTypes(List.copyOf(request.eventTypes()));
        }
        return SubscriptionResponse.from(repository.save(sub));
    }

    /**
     * Soft delete: disable the subscription rather than removing the row. Keeps delivery history
     * intact.
     */
    @Transactional
    public void delete(UUID id, SubscriptionOwnerType ownerType, UUID ownerId) {
        WebhookSubscription sub = loadOwned(id, ownerType, ownerId);
        sub.setEnabled(false);
        sub.setDisabledAt(Instant.now());
        sub.setDisabledReason("Disabled by owner");
        repository.save(sub);
    }

    @Transactional
    public String rotateSecret(UUID id, SubscriptionOwnerType ownerType, UUID ownerId) {
        WebhookSubscription sub = loadOwned(id, ownerType, ownerId);
        String newSecret = secretGenerator.generate();
        sub.setSecret(newSecret);
        repository.save(sub);
        return newSecret;
    }

    @Transactional
    public SubscriptionResponse enable(UUID id, SubscriptionOwnerType ownerType, UUID ownerId) {
        WebhookSubscription sub = loadOwned(id, ownerType, ownerId);
        sub.setEnabled(true);
        sub.setDisabledAt(null);
        sub.setDisabledReason(null);
        sub.setFailureCount(0);
        return SubscriptionResponse.from(repository.save(sub));
    }

    /** Lookup helper. Admin can access any subscription; a customer only their own. */
    WebhookSubscription loadOwned(UUID id, SubscriptionOwnerType ownerType, UUID ownerId) {
        WebhookSubscription sub =
                repository
                        .findById(id)
                        .orElseThrow(() -> new WebhookExceptions.SubscriptionNotFound(id));

        if (ownerType == SubscriptionOwnerType.ADMIN) {
            return sub;
        }
        if (sub.getOwnerType() != SubscriptionOwnerType.CUSTOMER
                || !sub.getOwnerId().equals(ownerId)) {
            throw new WebhookExceptions.SubscriptionNotFound(id);
        }
        return sub;
    }

    @Transactional(readOnly = true)
    public WebhookSubscription loadForDelivery(UUID id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new WebhookExceptions.SubscriptionNotFound(id));
    }

    @Transactional
    public WebhookSubscription createEntity(
            SubscriptionOwnerType ownerType, UUID ownerId, CreateSubscriptionRequest request) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(ownerType);
        sub.setOwnerId(ownerId);
        sub.setName(request.name());
        sub.setTargetUrl(request.targetUrl());
        sub.setEventTypes(List.copyOf(request.eventTypes()));
        sub.setSecret(secretGenerator.generate());
        sub.setEnabled(true);
        return repository.save(sub);
    }
}

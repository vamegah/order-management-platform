package com.example.omp.notification.webhook.delivery;

import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Small read-only façade used by the delivery worker. Kept separate so the worker does not depend
 * on WebhookSubscriptionService (which carries write/ownership concerns).
 */
@Component
public class WebhookSubscriptionServiceLookup {

    private final WebhookSubscriptionRepository repository;

    public WebhookSubscriptionServiceLookup(WebhookSubscriptionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<WebhookSubscription> findById(UUID id) {
        return repository.findById(id);
    }
}

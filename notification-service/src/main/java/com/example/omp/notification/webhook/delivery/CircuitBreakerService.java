package com.example.omp.notification.webhook.delivery;

import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-subscription circuit breaker.
 *
 * <p>After MAX_CONSECUTIVE_FAILURES consecutive delivery failures, the subscription is
 * auto-disabled. A successful delivery resets the counter to zero.
 *
 * <p>Auto-disable is sticky: the subscriber (or an admin) must explicitly re-enable.
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);
    public static final int MAX_CONSECUTIVE_FAILURES = 10;

    private final WebhookSubscriptionRepository repository;
    private final WebhookMetrics metrics;

    public CircuitBreakerService(WebhookSubscriptionRepository repository, WebhookMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID subscriptionId) {
        repository
                .findById(subscriptionId)
                .ifPresent(
                        sub -> {
                            if (sub.getFailureCount() != 0) {
                                sub.setFailureCount(0);
                                repository.save(sub);
                            }
                            sub.setLastDeliveryAt(Instant.now());
                            repository.save(sub);
                        });
    }

    /** Records a failure. Returns true if the subscription was disabled by this call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(UUID subscriptionId, String reason) {
        WebhookSubscription sub = repository.findById(subscriptionId).orElse(null);
        if (sub == null) {
            return false;
        }

        sub.setFailureCount(sub.getFailureCount() + 1);
        sub.setLastDeliveryAt(Instant.now());

        boolean disabledNow = false;
        if (sub.getFailureCount() >= MAX_CONSECUTIVE_FAILURES && sub.isEnabled()) {
            sub.setEnabled(false);
            sub.setDisabledAt(Instant.now());
            sub.setDisabledReason(
                    "Auto-disabled after " + MAX_CONSECUTIVE_FAILURES + " consecutive failures");
            disabledNow = true;
            log.warn(
                    "Auto-disabled subscription {} after {} failures: {}",
                    subscriptionId,
                    sub.getFailureCount(),
                    reason);
        }
        repository.save(sub);

        if (disabledNow) {
            metrics.recordSubscriptionDisabled();
        }
        return disabledNow;
    }
}

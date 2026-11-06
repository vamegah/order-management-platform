package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import com.example.omp.notification.webhook.delivery.CircuitBreakerService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbound webhook — circuit breaker.
 *
 * <p>Verifies: 1. N consecutive failures disable the subscription. 2. A subsequent event does not
 * attempt delivery; it goes straight to DLQ. 3. A successful delivery resets the failure counter.
 * 4. Re-enabling via the service API works and clears the counter.
 */
class OutboundWebhookCircuitBreakerIT extends OutboundWebhookBase {

    @Autowired WebhookSubscriptionRepository subscriptionRepository;

    @Test
    void consecutive_failures_disable_subscription() {
        WebhookSubscription sub = createSubscription("payment.confirmed");

        // Use the actual breaker via the application.
        var breaker = applicationContext.getBean(CircuitBreakerService.class);

        for (int i = 0; i < CircuitBreakerService.MAX_CONSECUTIVE_FAILURES; i++) {
            breaker.recordFailure(sub.getId(), "test");
        }

        WebhookSubscription reloaded = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getDisabledReason())
                .contains("Auto-disabled after " + CircuitBreakerService.MAX_CONSECUTIVE_FAILURES);
        assertThat(reloaded.getDisabledAt()).isNotNull();
    }

    @Test
    void disabled_subscription_deliveries_go_to_dlq_without_attempt() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");

        // Disable directly.
        sub.setEnabled(false);
        sub.setDisabledAt(java.time.Instant.now());
        sub.setDisabledReason("test");
        subscriptions.save(sub);

        // Exercise a delivery queued before the circuit opened; the matcher
        // deliberately excludes subscriptions that are already disabled.
        applicationContext
                .getBean(
                        com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher.class)
                .publish(
                        new com.example.omp.notification.webhook.matcher.DeliveryMessage(
                                java.util.UUID.randomUUID(),
                                sub.getId(),
                                "evt-disabled-" + java.util.UUID.randomUUID(),
                                "payment.confirmed",
                                1,
                                null));

        // No HTTP request should reach the subscriber.
        org.awaitility.Awaitility.await()
                .pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(subscriber.requestCount.get()).isZero());

        // One attempt row (FAILED_PERMANENT, "Subscription disabled").
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            var all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(1);
                            assertThat(all.get(0).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_PERMANENT);
                            assertThat(all.get(0).getErrorMessage())
                                    .contains("Subscription disabled");
                        });

        // DLQ entry with reason SUBSCRIPTION_DISABLED.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(dlqRepository.findAll())
                                        .anyMatch(
                                                e ->
                                                        "SUBSCRIPTION_DISABLED"
                                                                .equals(e.getReason())));
    }

    @Test
    void success_resets_failure_counter() {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        sub.setFailureCount(7);
        subscriptions.save(sub);

        var breaker = applicationContext.getBean(CircuitBreakerService.class);
        breaker.recordSuccess(sub.getId());

        WebhookSubscription reloaded = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(reloaded.getFailureCount()).isZero();
        assertThat(reloaded.isEnabled()).isTrue();
    }

    @Test
    void enabling_clears_disabled_state_and_counter() {
        WebhookSubscription sub = createSubscription("payment.confirmed");
        sub.setEnabled(false);
        sub.setDisabledAt(java.time.Instant.now());
        sub.setDisabledReason("test");
        sub.setFailureCount(10);
        subscriptions.save(sub);

        var service =
                applicationContext.getBean(
                        com.example.omp.notification.webhook.WebhookSubscriptionService.class);
        var response =
                service.enable(
                        sub.getId(),
                        com.example.omp.notification.webhook.SubscriptionOwnerType.ADMIN,
                        java.util.UUID.randomUUID());

        assertThat(response.enabled()).isTrue();
        assertThat(response.failureCount()).isZero();
        assertThat(response.disabledAt()).isNull();
        assertThat(response.disabledReason()).isNull();
    }

    @Autowired org.springframework.context.ApplicationContext applicationContext;
}

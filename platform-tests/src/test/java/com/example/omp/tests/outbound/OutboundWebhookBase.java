package com.example.omp.tests.outbound;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.SubscriptionOwnerType;
import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import com.example.omp.notification.webhook.WebhookDeliveryAttemptRepository;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.WebhookSubscriptionRepository;
import com.example.omp.notification.webhook.dlq.WebhookDlqRepository;
import com.example.omp.testsupport.KafkaTestConfig;
import com.example.omp.testsupport.MockSubscriber;
import com.example.omp.testsupport.ServiceTestProperties;
import com.example.omp.testsupport.SharedContainers;
import com.example.omp.testsupport.TestRetryScheduler;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for outbound webhook integration tests.
 *
 * <p>Boots the full notification-service with: - shared Postgres (notifications schema) - shared
 * Kafka and Redis - the fast-retry profile, so backoff is skipped in tests
 *
 * <p>Provides helpers to: - create a subscription targeting a MockSubscriber - publish a domain
 * event to the payment/order/inventory topics - read back delivery attempts and DLQ entries
 */
@SpringBootTest(
        classes = com.example.omp.notification.NotificationServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.config.location=file:notification-service/src/main/resources/application.yml,classpath:application-test.yml")
@ActiveProfiles({"test", "test-fast-retry"})
@Import({KafkaTestConfig.class, TestRetryScheduler.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class OutboundWebhookBase {

    static {
        SharedContainers.ensureStarted();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ServiceTestProperties.register(registry, "notification-service", "notifications");

        // Bound the retry window so exhausted-retry tests finish quickly.
        registry.add("webhook.delivery.timeout-seconds", () -> "2");
        registry.add("webhook.circuit-breaker.max-consecutive-failures", () -> "10");
    }

    @Autowired WebhookSubscriptionRepository subscriptions;
    @Autowired WebhookDeliveryAttemptRepository attempts;
    @Autowired WebhookDlqRepository dlqRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbc;

    protected MockSubscriber subscriber;

    @BeforeEach
    void baseSetUp() throws Exception {
        // Reset state between tests.
        jdbc.execute("TRUNCATE TABLE notifications.webhook_delivery_attempt CASCADE");
        jdbc.execute("TRUNCATE TABLE notifications.webhook_delivery_dlq CASCADE");
        jdbc.execute("TRUNCATE TABLE notifications.webhook_subscription CASCADE");
        subscriber = new MockSubscriber();
    }

    @AfterEach
    void baseTearDown() {
        try {
            com.example.omp.testsupport.KafkaTestHelper.awaitWebhookConsumersCaughtUp(
                    SharedContainers.kafkaBootstrap(), "platform-tests");
        } finally {
            if (subscriber != null) {
                subscriber.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    protected WebhookSubscription createSubscription(String... eventTypes) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setOwnerType(SubscriptionOwnerType.CUSTOMER);
        sub.setOwnerId(UUID.randomUUID());
        sub.setName("test-subscription");
        sub.setTargetUrl(subscriber.url());
        sub.setSecret("0123456789abcdef0123456789abcdef0123456789");
        sub.setEventTypes(List.of(eventTypes));
        sub.setEnabled(true);
        sub.setFailureCount(0);
        return subscriptions.save(sub);
    }

    protected void publishEvent(String topic, String eventId, String eventType, String dataJson) {
        String payload =
                """
                {
                  "eventId":"%s",
                  "eventType":"%s",
                  "aggregateId":"%s",
                  "occurredAt":"2026-01-01T00:00:00Z",
                  "schemaVersion":1,
                  "data":%s
                }
                """
                        .formatted(eventId, eventType, UUID.randomUUID(), dataJson);
        try {
            kafkaTemplate.send(topic, eventId, payload).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Test event was not acknowledged", e);
        }
    }

    protected List<WebhookDeliveryAttempt> attemptsFor(UUID subscriptionId) {
        return attempts.findBySubscriptionIdOrderByScheduledAtDesc(
                        subscriptionId, PageRequest.of(0, 100))
                .toList();
    }

    protected long countAttempts(UUID subscriptionId, DeliveryStatus status) {
        return attemptsFor(subscriptionId).stream().filter(a -> a.getStatus() == status).count();
    }
}

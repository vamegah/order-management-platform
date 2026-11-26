package com.example.omp.notification.webhook.delivery;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookDeliveryAttempt;
import com.example.omp.notification.webhook.WebhookDeliveryAttemptRepository;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.dlq.DlqService;
import com.example.omp.notification.webhook.events.WebhookTestEvent;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes delivery messages, performs the HTTP POST, records the attempt, and routes the delivery
 * on failure (retry topic or DLQ).
 */
@Component
public class WebhookDeliveryWorker {
    private final com.example.omp.shared.events.tracing.EventTracing tracing =
            new com.example.omp.shared.events.tracing.EventTracing();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureTracing(
            io.micrometer.tracing.Tracer tracer,
            io.micrometer.tracing.propagation.Propagator propagator) {
        tracing.configure(tracer, propagator);
    }

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookSubscriptionServiceLookup subscriptionLookup;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookSigner signer;
    private final WebhookHttpClient httpClient;
    private final RetryScheduler retryScheduler;
    private final CircuitBreakerService circuitBreaker;
    private final DlqService dlqService;
    private final ObjectMapper objectMapper;
    private final WebhookMetrics metrics;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher publisher;

    public WebhookDeliveryWorker(
            WebhookSubscriptionServiceLookup subscriptionLookup,
            WebhookDeliveryAttemptRepository attemptRepository,
            WebhookSigner signer,
            WebhookHttpClient httpClient,
            RetryScheduler retryScheduler,
            CircuitBreakerService circuitBreaker,
            DlqService dlqService,
            ObjectMapper objectMapper,
            WebhookMetrics metrics,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher publisher) {
        this.subscriptionLookup = subscriptionLookup;
        this.attemptRepository = attemptRepository;
        this.signer = signer;
        this.httpClient = httpClient;
        this.retryScheduler = retryScheduler;
        this.circuitBreaker = circuitBreaker;
        this.dlqService = dlqService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    @KafkaListener(
            topics = {
                WebhookKafkaTopics.DELIVERIES,
                WebhookKafkaTopics.RETRY_1,
                WebhookKafkaTopics.RETRY_2,
                WebhookKafkaTopics.RETRY_3,
                WebhookKafkaTopics.RETRY_4,
                WebhookKafkaTopics.RETRY_5
            },
            groupId = "${spring.kafka.consumer.group-id}-webhook-delivery",
            containerFactory = "webhookDeliveryKafkaListenerContainerFactory")
    @org.springframework.transaction.annotation.Transactional
    public void onDelivery(String messageJson) {
        DeliveryMessage message;
        try {
            message = objectMapper.readValue(messageJson, DeliveryMessage.class);
            message.validate();
        } catch (Exception e) {
            throw new com.example.omp.shared.events.EventContract.InvalidEventException();
        }

        // Honor scheduled delay (retry topics carry a scheduledAtMillis).
        if (message.scheduledAtMillis() != null) {
            long waitMs = message.scheduledAtMillis() - System.currentTimeMillis();
            if (waitMs > 0) {
                publisher.publish(message);
                return;
            }
        }

        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                        Boolean.class,
                        message.deliveryId().toString())))
            throw new IllegalStateException("Delivery is already in progress");
        try {
            var parents =
                    jdbc.queryForList(
                            "SELECT traceparent FROM webhook_event_payload WHERE event_id=?",
                            message.eventId());
            String parent =
                    parents.isEmpty() ? null : (String) parents.getFirst().get("traceparent");
            tracing.run(parent, "webhook.deliver", () -> processDelivery(message));
        } catch (Exception e) {
            log.error(
                    "Unexpected failure processing delivery {}: {}",
                    message.deliveryId(),
                    e.getClass().getSimpleName());
            // Rethrow for Kafka retry. The delivery is at-least-once; duplicates
            // are prevented by the SUCCESS check below.
            throw new IllegalStateException("Webhook delivery processing failed");
        }
    }

    private void processDelivery(DeliveryMessage message) {
        UUID deliveryId = message.deliveryId();

        // Idempotency: if any attempt for this delivery already succeeded, skip.
        if (attemptRepository.existsByDeliveryIdAndStatus(deliveryId, DeliveryStatus.SUCCESS)
                || Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM webhook_delivery_receipt WHERE delivery_id=?)",
                                Boolean.class,
                                deliveryId))) {
            log.debug("Delivery {} already succeeded; skipping duplicate", deliveryId);
            return;
        }

        WebhookSubscription sub =
                subscriptionLookup.findById(message.subscriptionId()).orElse(null);
        if (sub == null) {
            log.warn(
                    "Subscription {} no longer exists; aborting delivery {}",
                    message.subscriptionId(),
                    deliveryId);
            dlqService.persist(message, "SUBSCRIPTION_NOT_FOUND", "FAILED_PERMANENT");
            return;
        }

        if (!sub.isEnabled()) {
            log.info("Subscription {} is disabled; DLQ-ing delivery {}", sub.getId(), deliveryId);
            WebhookDeliveryAttempt attempt =
                    recordAttempt(
                            message,
                            sub,
                            DeliveryStatus.FAILED_PERMANENT,
                            null,
                            null,
                            "Subscription disabled",
                            buildPayload(message, sub),
                            null);
            dlqService.persist(message, "SUBSCRIPTION_DISABLED", "FAILED_PERMANENT");
            metrics.recordDelivery(message.eventType(), "failed_permanent");
            return;
        }

        long timestampSeconds = Instant.now().getEpochSecond();
        String rawBody = buildPayload(message, sub);
        String signature = signer.sign(sub.getSecret(), timestampSeconds, rawBody);

        Map<String, String> headers =
                buildHeaders(message, sub, timestampSeconds, signature, rawBody);

        WebhookDeliveryAttempt attempt =
                recordAttempt(
                        message, sub, DeliveryStatus.PENDING, null, null, null, rawBody, signature);
        attempt.setStartedAt(Instant.now());
        attemptRepository.save(attempt);

        DeliveryResult result = httpClient.post(sub.getTargetUrl(), rawBody, headers);

        attempt.setCompletedAt(Instant.now());
        attempt.setResponseStatus(result.httpStatus());
        attempt.setResponseBody(result.responseBody());
        attempt.setErrorMessage(result.errorMessage());

        if (result.outcome() != DeliveryOutcome.SUCCESS) tracing.failed();
        switch (result.outcome()) {
            case SUCCESS -> {
                attempt.setStatus(DeliveryStatus.SUCCESS);
                attemptRepository.save(attempt);
                jdbc.update(
                        "INSERT INTO webhook_delivery_receipt(delivery_id,subscription_id,event_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                        deliveryId,
                        sub.getId(),
                        message.eventId());
                circuitBreaker.recordSuccess(sub.getId());
                metrics.recordDelivery(message.eventType(), "success");
                metrics.recordDeliveryDuration(sub.getId(), attempt);
            }
            case PERMANENT -> {
                attempt.setStatus(DeliveryStatus.FAILED_PERMANENT);
                attemptRepository.save(attempt);
                circuitBreaker.recordFailure(sub.getId(), "permanent failure");
                dlqService.persist(message, describePermanent(result), "FAILED_PERMANENT");
                metrics.recordDelivery(message.eventType(), "failed_permanent");
            }
            case RETRYABLE -> {
                attempt.setStatus(DeliveryStatus.FAILED_RETRYABLE);
                attemptRepository.save(attempt);
                circuitBreaker.recordFailure(sub.getId(), "retryable failure");

                if (message.attemptNumber() >= RetryScheduler.maxAttempts()) {
                    dlqService.persist(message, describeRetryable(result), "FAILED_RETRYABLE");
                    metrics.recordDelivery(message.eventType(), "failed_retryable");
                    metrics.recordDlq("retries_exhausted");
                } else {
                    retryScheduler.scheduleRetry(
                            message.attemptNumber(),
                            message.deliveryId(),
                            message.subscriptionId(),
                            message.eventId(),
                            message.eventType(),
                            result.retryAfterMillis());
                    metrics.recordDelivery(message.eventType(), "failed_retryable");
                }
            }
        }
    }

    private Map<String, String> buildHeaders(
            DeliveryMessage message,
            WebhookSubscription sub,
            long timestampSeconds,
            String signature,
            String rawBody) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Signature-256", signature);
        String signatures = "v1=" + signature.substring("sha256=".length());
        if (sub.getPreviousSecret() != null
                && sub.getPreviousSecretValidUntil() != null
                && sub.getPreviousSecretValidUntil().isAfter(Instant.now()))
            signatures +=
                    ",v1="
                            + signer.sign(sub.getPreviousSecret(), timestampSeconds, rawBody)
                                    .substring("sha256=".length());
        headers.put("X-Webhook-Signatures", signatures);
        headers.put("X-Webhook-Key-Version", Integer.toString(sub.getKeyVersion()));
        headers.put("X-Timestamp", Long.toString(timestampSeconds));
        headers.put("X-Webhook-Id", message.eventId());
        headers.put("X-Webhook-Delivery-Id", message.deliveryId().toString());
        headers.put("X-Webhook-Event", message.eventType());
        if (tracing.current() != null) headers.put("traceparent", tracing.current());
        return headers;
    }

    /**
     * Builds the JSON envelope sent to the subscriber.
     *
     * <p>The envelope shape is deliberately stable; subscribers parse it once.
     */
    private String buildPayload(DeliveryMessage message, WebhookSubscription sub) {
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("schemaVersion", 1);
            envelope.put("eventId", message.eventId());
            envelope.put("eventType", message.eventType());
            envelope.put("deliveryId", message.deliveryId().toString());
            envelope.put("subscriptionId", sub.getId().toString());
            envelope.put("attempt", message.attemptNumber());
            envelope.put("occurredAt", Instant.now().toString());

            if (WebhookTestEvent.EVENT_TYPE.equals(message.eventType())) {
                envelope.put("data", WebhookTestEvent.payload(sub.getId()));
            } else {
                var source =
                        jdbc.queryForList(
                                "SELECT payload::text AS payload,occurred_at FROM webhook_event_payload WHERE event_id=?",
                                message.eventId());
                if (source.isEmpty()) envelope.put("data", Map.of("eventId", message.eventId()));
                else {
                    envelope.put(
                            "data",
                            objectMapper.readTree((String) source.getFirst().get("payload")));
                    envelope.put(
                            "occurredAt",
                            ((java.sql.Timestamp) source.getFirst().get("occurred_at"))
                                    .toInstant()
                                    .toString());
                }
            }

            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize webhook payload", e);
        }
    }

    private WebhookDeliveryAttempt recordAttempt(
            DeliveryMessage message,
            WebhookSubscription sub,
            DeliveryStatus status,
            Integer responseStatus,
            String responseBody,
            String errorMessage,
            String payload,
            String signature) {
        WebhookDeliveryAttempt attempt = new WebhookDeliveryAttempt();
        attempt.setDeliveryId(message.deliveryId());
        attempt.setSubscriptionId(sub.getId());
        attempt.setEventId(message.eventId());
        attempt.setEventType(message.eventType());
        attempt.setAttemptNumber(message.attemptNumber());
        attempt.setStatus(status);
        attempt.setResponseStatus(responseStatus);
        attempt.setResponseBody(responseBody);
        attempt.setErrorMessage(errorMessage);
        attempt.setPayload(payload == null ? "{}" : payload);
        attempt.setSignature(signature == null ? "" : signature);
        attempt.setScheduledAt(Instant.now());
        return attemptRepository.save(attempt);
    }

    private static String describePermanent(DeliveryResult r) {
        return r.httpStatus() != null
                ? "Permanent HTTP " + r.httpStatus()
                : "Permanent error: " + r.errorMessage();
    }

    private static String describeRetryable(DeliveryResult r) {
        return r.httpStatus() != null
                ? "Retries exhausted (last HTTP " + r.httpStatus() + ")"
                : "Retries exhausted: " + r.errorMessage();
    }
}

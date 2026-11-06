package com.example.omp.notification.webhook.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.*;
import com.example.omp.notification.webhook.dlq.DlqService;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WebhookDeliveryWorkerTest {
    final WebhookSubscriptionServiceLookup lookup = mock(WebhookSubscriptionServiceLookup.class);
    final WebhookDeliveryAttemptRepository attempts = mock(WebhookDeliveryAttemptRepository.class);
    final WebhookHttpClient http = mock(WebhookHttpClient.class);
    final RetryScheduler retries = mock(RetryScheduler.class);
    final CircuitBreakerService breaker = mock(CircuitBreakerService.class);
    final DlqService dlq = mock(DlqService.class);
    final WebhookMetrics metrics = mock(WebhookMetrics.class);
    final ObjectMapper mapper = new ObjectMapper();
    final WebhookDeliveryWorker worker =
            new WebhookDeliveryWorker(
                    lookup,
                    attempts,
                    new WebhookSigner(),
                    http,
                    retries,
                    breaker,
                    dlq,
                    mapper,
                    metrics);
    final WebhookSubscription sub = new WebhookSubscription();
    final UUID deliveryId = UUID.randomUUID();
    WebhookDeliveryAttempt recorded;

    @BeforeEach
    void subscription() {
        sub.setId(UUID.randomUUID());
        sub.setTargetUrl("https://subscriber.example/hook");
        sub.setSecret("abcdefghijklmnopqrstuvwxyz0123456789");
        when(lookup.findById(sub.getId())).thenReturn(Optional.of(sub));
        when(attempts.save(any())).thenAnswer(invocation -> recorded = invocation.getArgument(0));
    }

    DeliveryMessage message(int attempt) {
        return new DeliveryMessage(
                deliveryId, sub.getId(), "event-1", "payment.confirmed", attempt, null);
    }

    void deliver(int attempt, DeliveryResult result) throws Exception {
        when(http.post(eq(sub.getTargetUrl()), anyString(), anyMap())).thenReturn(result);
        worker.onDelivery(mapper.writeValueAsString(message(attempt)));
    }

    @Test
    void successfulPostRecordsAuditAndResetsFailures() throws Exception {
        deliver(1, DeliveryResult.success(204, ""));
        assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(recorded.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(recorded.getEventId()).isEqualTo("event-1");
        assertThat(recorded.getResponseStatus()).isEqualTo(204);
        assertThat(recorded.getStartedAt()).isNotNull();
        assertThat(recorded.getCompletedAt()).isAfterOrEqualTo(recorded.getStartedAt());
        assertThat(mapper.readTree(recorded.getPayload()).path("deliveryId").asText())
                .isEqualTo(deliveryId.toString());
        verify(breaker).recordSuccess(sub.getId());
        verifyNoInteractions(retries, dlq);
    }

    @Test
    void alreadySuccessfulDeliveryDoesNotPostAgain() throws Exception {
        when(attempts.existsByDeliveryIdAndStatus(deliveryId, DeliveryStatus.SUCCESS))
                .thenReturn(true);
        worker.onDelivery(mapper.writeValueAsString(message(1)));
        verifyNoInteractions(lookup, http, retries, dlq);
        verify(attempts, never()).save(any());
    }

    @Test
    void deletedSubscriptionGoesToDlqWithoutPosting() throws Exception {
        when(lookup.findById(sub.getId())).thenReturn(Optional.empty());
        worker.onDelivery(mapper.writeValueAsString(message(1)));
        verify(dlq).persist(message(1), "SUBSCRIPTION_NOT_FOUND", "FAILED_PERMANENT");
        verifyNoInteractions(http, retries);
    }

    @Test
    void disabledSubscriptionRecordsPermanentFailureWithoutPosting() throws Exception {
        sub.setEnabled(false);
        worker.onDelivery(mapper.writeValueAsString(message(1)));
        assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.FAILED_PERMANENT);
        assertThat(recorded.getErrorMessage()).isEqualTo("Subscription disabled");
        verify(dlq).persist(message(1), "SUBSCRIPTION_DISABLED", "FAILED_PERMANENT");
        verifyNoInteractions(http, retries);
    }

    @Test
    void retryableFailurePreservesDeliveryIdentityAndRetryAfter() throws Exception {
        deliver(2, DeliveryResult.retryable(429, "busy", 2000L));
        assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.FAILED_RETRYABLE);
        assertThat(recorded.getResponseBody()).isEqualTo("busy");
        verify(retries)
                .scheduleRetry(2, deliveryId, sub.getId(), "event-1", "payment.confirmed", 2000L);
        verify(breaker).recordFailure(sub.getId(), "retryable failure");
        verifyNoInteractions(dlq);
    }

    static List<DeliveryResult> finalFailures() {
        return List.of(
                DeliveryResult.retryable(503, "unavailable"),
                DeliveryResult.retryableError("timeout"),
                DeliveryResult.permanent(410, "gone"),
                DeliveryResult.permanentError("TLS failed"));
    }

    @ParameterizedTest
    @MethodSource("finalFailures")
    void exhaustedOrPermanentFailureIsDeadLettered(DeliveryResult result) throws Exception {
        deliver(6, result);
        assertThat(recorded.getStatus())
                .isIn(DeliveryStatus.FAILED_PERMANENT, DeliveryStatus.FAILED_RETRYABLE);
        verify(dlq).persist(eq(message(6)), anyString(), eq(recorded.getStatus().name()));
        verifyNoInteractions(retries);
    }

    @Test
    void auditPersistenceFailurePropagatesForKafkaRetryBeforeHttp() throws Exception {
        when(attempts.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> worker.onDelivery(mapper.writeValueAsString(message(1))))
                .hasRootCauseMessage("database unavailable");
        verifyNoInteractions(http, retries, dlq);
    }
}

package com.example.omp.notification.webhook.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.matcher.*;
import com.example.omp.notification.webhook.metrics.WebhookMetrics;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class RetrySchedulerTest {
    final WebhookDeliveryPublisher publisher = mock(WebhookDeliveryPublisher.class);
    final WebhookMetrics metrics = mock(WebhookMetrics.class);
    final RetryScheduler scheduler = new RetryScheduler(publisher, metrics);

    @ParameterizedTest
    @CsvSource({"1,60000", "2,300000", "3,1800000", "4,7200000", "5,86400000"})
    void retriesKeepIdentityAndUseDocumentedJitterWindow(int current, long baseDelay) {
        UUID delivery = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        long before = System.currentTimeMillis();
        scheduler.scheduleRetry(current, delivery, sub, "event", "payment.confirmed", null);
        long after = System.currentTimeMillis();
        var captor = ArgumentCaptor.forClass(DeliveryMessage.class);
        verify(publisher)
                .publish(eq("webhooks.outbound.deliveries.retry-" + current), captor.capture());
        var message = captor.getValue();
        assertThat(message.deliveryId()).isEqualTo(delivery);
        assertThat(message.subscriptionId()).isEqualTo(sub);
        assertThat(message.eventId()).isEqualTo("event");
        assertThat(message.attemptNumber()).isEqualTo(current + 1);
        assertThat(message.scheduledAtMillis())
                .isBetween(before + baseDelay * 9 / 10, after + baseDelay * 11 / 10);
    }

    @Test
    void immediateRetryAfterAvoidsWaitingAndUnsupportedAttemptDoesNotPublish() {
        UUID id = UUID.randomUUID();
        long before = System.currentTimeMillis();
        scheduler.scheduleRetry(1, id, id, "event", "payment.confirmed", 0L);
        var captor = ArgumentCaptor.forClass(DeliveryMessage.class);
        verify(publisher).publish(anyString(), captor.capture());
        assertThat(captor.getValue().scheduledAtMillis())
                .isBetween(before, System.currentTimeMillis());
        clearInvocations(publisher);
        assertThatThrownBy(
                        () ->
                                scheduler.scheduleRetry(
                                        6, id, id, "event", "payment.confirmed", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publisher);
    }
}

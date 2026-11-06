package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A real subscriber exceeds the configured two-second request timeout. */
class OutboundWebhookTimeoutIT extends OutboundWebhookBase {
    @Test
    void hung_endpoint_times_out_and_is_retried() {
        var sub = createSubscription("payment.confirmed");
        subscriber.enqueueResponse(200, "late", Duration.ofSeconds(5));
        subscriber.enqueueResponse(200, "recovered");
        publishEvent(
                "payments.events.payment",
                UUID.randomUUID().toString(),
                "payment.confirmed",
                "{\"paymentId\":\"p-timeout\"}");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            var all = attemptsFor(sub.getId());
                            assertThat(all).hasSize(2);
                            assertThat(all.get(0).getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
                            assertThat(all.get(1).getStatus())
                                    .isEqualTo(DeliveryStatus.FAILED_RETRYABLE);
                            assertThat(all.get(1).getErrorMessage())
                                    .containsIgnoringCase("timeout");
                            assertThat(subscriber.requestCount.get()).isEqualTo(2);
                        });
    }
}

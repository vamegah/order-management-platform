package com.example.omp.tests.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.DeliveryStatus;
import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.dlq.DlqService;
import com.example.omp.notification.webhook.dlq.WebhookDlqEntry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbound webhook — DLQ replay.
 *
 * <p>Verifies: 1. A permanent failure creates a DLQ entry. 2. Replaying the entry republishes it to
 * the deliveries topic with attemptNumber = 1 and a fresh delivery attempt. 3. The replayed entry
 * is marked replayed. 4. Replaying an already-replayed entry is rejected.
 */
class OutboundWebhookDlqReplayIT extends OutboundWebhookBase {

    @Autowired DlqService dlqService;

    @Test
    void replay_redelivers_and_marks_entry() throws Exception {
        WebhookSubscription sub = createSubscription("payment.confirmed");

        // First attempt: 404 -> permanent failure -> DLQ.
        subscriber.enqueueResponse(404, "not found");

        String eventId = "evt-replay-" + java.util.UUID.randomUUID();
        publishEvent(
                "payments.events.payment",
                eventId,
                "payment.confirmed",
                "{\"paymentId\":\"p-replay\"}");

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(dlqRepository.findAll()).hasSize(1));

        WebhookDlqEntry dlq = dlqRepository.findAll().get(0);
        assertThat(dlq.getReplayedAt()).isNull();
        assertThat(dlq.getFinalStatus()).isEqualTo("FAILED_PERMANENT");

        // Now the subscriber is healthy.
        subscriber.enqueueResponse(200, "ok");

        // Replay.
        dlqService.replay(dlq.getDeliveryId(), "test-admin");

        // The replayed delivery should be re-attempted and succeed.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(countAttempts(sub.getId(), DeliveryStatus.SUCCESS))
                                        .isGreaterThanOrEqualTo(1));

        // DLQ entry marked replayed.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            WebhookDlqEntry reloaded =
                                    dlqRepository
                                            .findByDeliveryId(dlq.getDeliveryId())
                                            .orElseThrow();
                            assertThat(reloaded.getReplayedAt()).isNotNull();
                            assertThat(reloaded.getReplayedBy()).isEqualTo("test-admin");
                        });
    }

    @Test
    void replay_of_already_replayed_entry_is_rejected() throws Exception {
        WebhookSubscription sub = createSubscription("order.placed");
        subscriber.enqueueResponse(422, "unprocessable");

        publishEvent(
                "orders.events.order",
                "evt-replay-dup-" + java.util.UUID.randomUUID(),
                "order.placed",
                "{\"orderId\":\"o-r\"}");

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(dlqRepository.findAll()).hasSize(1));
        WebhookDlqEntry dlq = dlqRepository.findAll().get(0);

        subscriber.enqueueResponse(200);
        dlqService.replay(dlq.getDeliveryId(), "admin-1");

        // Second replay must be rejected.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dlqService.replay(dlq.getDeliveryId(), "admin-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already replayed");
    }

    @Test
    void replay_of_unknown_delivery_is_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dlqService.replay(java.util.UUID.randomUUID(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No DLQ entry");
    }
}

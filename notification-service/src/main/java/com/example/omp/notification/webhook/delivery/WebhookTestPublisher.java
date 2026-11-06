package com.example.omp.notification.webhook.delivery;

import com.example.omp.notification.webhook.WebhookSubscription;
import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.notification.webhook.events.WebhookTestEvent;
import com.example.omp.notification.webhook.matcher.DeliveryIdGenerator;
import com.example.omp.notification.webhook.matcher.DeliveryMessage;
import com.example.omp.notification.webhook.matcher.WebhookDeliveryPublisher;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WebhookTestPublisher {

    private final WebhookDeliveryPublisher publisher;
    private final DeliveryIdGenerator idGenerator;

    public WebhookTestPublisher(
            WebhookDeliveryPublisher publisher, DeliveryIdGenerator idGenerator) {
        this.publisher = publisher;
        this.idGenerator = idGenerator;
    }

    public UUID publishTest(WebhookSubscription sub) {
        String eventId = "test-" + UUID.randomUUID();
        UUID deliveryId = idGenerator.generate(sub.getId(), eventId);

        DeliveryMessage message =
                new DeliveryMessage(
                        deliveryId, sub.getId(), eventId, WebhookTestEvent.EVENT_TYPE, 1, null);

        publisher.publish(WebhookKafkaTopics.DELIVERIES, message);
        return deliveryId;
    }
}

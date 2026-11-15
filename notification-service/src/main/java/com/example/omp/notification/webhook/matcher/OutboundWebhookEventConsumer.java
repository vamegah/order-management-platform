package com.example.omp.notification.webhook.matcher;

import com.example.omp.notification.durable.DomainEventIngestion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OutboundWebhookEventConsumer {
    private final DomainEventIngestion ingestion;

    public OutboundWebhookEventConsumer(DomainEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @KafkaListener(
            topics = {
                "${webhook.topics.events.order:orders.events.order}",
                "${webhook.topics.events.payment:payments.events.payment}",
                "${webhook.topics.events.inventory:inventory.events.reservation}"
            },
            groupId = "${spring.kafka.consumer.group-id}-outbound-webhook",
            containerFactory = "webhookEventKafkaListenerContainerFactory")
    public void onEvent(ConsumerRecord<String, String> record) {
        ingestion.accept("webhook", record.value(), record.topic());
    }
}

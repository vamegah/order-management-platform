package com.example.omp.notification.kafka;

import com.example.omp.notification.durable.DomainEventIngestion;
import com.example.omp.shared.events.KafkaTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventConsumer {
    private final DomainEventIngestion ingestion;

    public InventoryEventConsumer(DomainEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_EVENTS_RESERVATION,
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${spring.kafka.listener.concurrency:3}")
    public void onInventoryEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {
        ingestion.accept("live", payload, KafkaTopics.INVENTORY_EVENTS_RESERVATION);
        ack.acknowledge();
    }
}

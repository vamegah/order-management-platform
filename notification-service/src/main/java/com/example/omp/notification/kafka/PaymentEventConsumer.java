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
public class PaymentEventConsumer {
    private final DomainEventIngestion ingestion;

    public PaymentEventConsumer(DomainEventIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENTS_EVENTS_PAYMENT,
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${spring.kafka.listener.concurrency:3}")
    public void onPaymentEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {
        ingestion.accept("live", payload, KafkaTopics.PAYMENTS_EVENTS_PAYMENT);
        ack.acknowledge();
    }
}

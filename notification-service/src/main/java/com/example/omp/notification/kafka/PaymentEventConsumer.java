package com.example.omp.notification.kafka;

import com.example.omp.notification.redis.RedisEventPublisher;
import com.example.omp.shared.events.KafkaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Kafka consumer for payment events. */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final RedisEventPublisher redisPublisher;
    private final MeterRegistry metrics;

    public PaymentEventConsumer(RedisEventPublisher redisPublisher, MeterRegistry metrics) {
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
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

        log.debug("Received payment event: partition={} offset={}", partition, offset);

        try {
            redisPublisher.publishToPaymentsChannel(payload);
            metrics.counter("kafka.events.consumed", "topic", "payments").increment();
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to forward payment event to Redis: {}", e.getMessage(), e);
            metrics.counter(
                            "kafka.events.failed",
                            "topic",
                            "payments",
                            "exception",
                            e.getClass().getSimpleName())
                    .increment();
            throw e;
        }
    }
}

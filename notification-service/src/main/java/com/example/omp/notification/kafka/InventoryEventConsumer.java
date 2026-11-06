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

/**
 * Kafka consumer for inventory events. Same structure as {@link OrderEventConsumer}, different
 * topic and channel.
 */
@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final RedisEventPublisher redisPublisher;
    private final MeterRegistry metrics;

    public InventoryEventConsumer(RedisEventPublisher redisPublisher, MeterRegistry metrics) {
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
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

        log.debug("Received inventory event: partition={} offset={}", partition, offset);

        try {
            redisPublisher.publishToInventoryChannel(payload);
            metrics.counter("kafka.events.consumed", "topic", "inventory").increment();
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to forward inventory event to Redis: {}", e.getMessage(), e);
            metrics.counter(
                            "kafka.events.failed",
                            "topic",
                            "inventory",
                            "exception",
                            e.getClass().getSimpleName())
                    .increment();
            throw e;
        }
    }
}

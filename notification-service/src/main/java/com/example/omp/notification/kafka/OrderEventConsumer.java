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
 * Kafka consumer for order events.
 *
 * <p>Subscribes to {@code orders.events.order}. For each event:
 *
 * <ol>
 *   <li>Publishes to the Redis channel {@code notifications:orders}.
 *   <li>Commits the Kafka offset.
 * </ol>
 *
 * <p><b>The consumer does NOT dispatch to WebSocket clients directly.</b> Dispatch happens via
 * Redis: all replicas (including this one) receive the event via the Redis subscription and
 * dispatch locally. This ensures a client connected to any replica receives the event exactly once.
 *
 * <p><b>Offset commit:</b> manual, after Redis publish. If the publish fails (Redis temporarily
 * down), the offset is not committed and Kafka re-delivers. A duplicate Redis publish is harmless —
 * subscribers handle duplicates idempotently.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final RedisEventPublisher redisPublisher;
    private final MeterRegistry metrics;

    public OrderEventConsumer(RedisEventPublisher redisPublisher, MeterRegistry metrics) {
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDERS_EVENTS_ORDER,
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${spring.kafka.listener.concurrency:3}")
    public void onOrderEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.debug("Received order event: partition={} offset={}", partition, offset);

        try {
            // Forward to Redis. All replicas (including this one) will
            // dispatch to their local subscribers from the Redis subscriber.
            redisPublisher.publishToOrdersChannel(payload);

            metrics.counter("kafka.events.consumed", "topic", "orders").increment();

            // Commit the offset only after successful Redis publish.
            ack.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to forward order event to Redis (will be retried): {}",
                    e.getMessage(),
                    e);
            metrics.counter(
                            "kafka.events.failed",
                            "topic",
                            "orders",
                            "exception",
                            e.getClass().getSimpleName())
                    .increment();

            // Do NOT ack. Kafka will re-deliver after session timeout.
            // The DefaultErrorHandler will retry in-place up to 3 times
            // before giving up. If we get here, the error handler already
            // exhausted retries.
            throw e;
        }
    }
}

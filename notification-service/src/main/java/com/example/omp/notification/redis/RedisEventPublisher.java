package com.example.omp.notification.redis;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes event payloads to Redis pub/sub channels.
 *
 * <p>Called by the Kafka consumers. The payload is the raw JSON string received from Kafka (not
 * re-serialized) — this avoids a round-trip through our Jackson configuration and preserves field
 * ordering.
 *
 * <p>Subscribers receive the JSON and deserialize via {@code EventDeserializationSupport}.
 */
@Component
public class RedisEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;

    public RedisEventPublisher(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public void publishToOrdersChannel(String payload) {
        publish(RedisChannels.ORDERS, payload);
    }

    public void publishToInventoryChannel(String payload) {
        publish(RedisChannels.INVENTORY, payload);
    }

    public void publishToPaymentsChannel(String payload) {
        publish(RedisChannels.PAYMENTS, payload);
    }

    private void publish(String channel, String payload) {
        try {
            Long receivers = redis.convertAndSend(channel, payload);
            metrics.counter("redis.events.published", "channel", channel).increment();

            log.debug("Published to Redis channel {} ({} receivers)", channel, receivers);
        } catch (Exception e) {
            metrics.counter("redis.publish.errors", "channel", channel).increment();
            log.error("Redis publish failed on channel {}: {}", channel, e.getMessage());
            throw e;
        }
    }
}

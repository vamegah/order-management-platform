package com.example.omp.notification.redis;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Registers the {@link RedisEventSubscriber} with the Redis message listener container for all
 * event channels.
 *
 * <p>Runs at startup (after the container bean is initialized). Registers the subscriber for {@link
 * RedisChannels#ALL}.
 *
 * <p><b>Why a separate class?</b> The subscriber itself doesn't know which channels it subscribes
 * to — that's a wiring concern. The container is created in {@code RedisConfig} (Batch 8.2); this
 * class completes the wiring once both beans are available.
 */
@Component
public class RedisSubscriberRegistration {

    private static final Logger log = LoggerFactory.getLogger(RedisSubscriberRegistration.class);

    private final RedisMessageListenerContainer container;
    private final RedisEventSubscriber subscriber;
    private final RedisConnectionFactory connectionFactory;

    public RedisSubscriberRegistration(
            RedisMessageListenerContainer container,
            RedisEventSubscriber subscriber,
            RedisConnectionFactory connectionFactory) {
        this.container = container;
        this.subscriber = subscriber;
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    void register() {
        for (String channel : RedisChannels.ALL) {
            container.addMessageListener(subscriber, new ChannelTopic(channel));
            log.info("Subscribed to Redis channel: {}", channel);
        }

        // Sanity check: verify the container is started. Spring starts it
        // automatically as part of its lifecycle, but we assert here to
        // catch configuration issues early.
        if (!container.isRunning()) {
            log.warn(
                    "Redis message listener container is not running after registration. "
                            + "Check connection factory configuration.");
        }
    }
}

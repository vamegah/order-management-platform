package com.example.omp.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis configuration for pub/sub.
 *
 * <p>notification-service uses Redis for <b>cross-replica fan-out</b>: when a replica receives a
 * Kafka event, it publishes the event to a Redis channel. All replicas subscribe to that channel;
 * each pushes the event to its own local WebSocket clients.
 *
 * <p><b>Why cross-replica fan-out matters:</b> if a client is connected to replica A, but the Kafka
 * event was consumed on replica B, the client would miss the event without this mechanism. Redis
 * pub/sub solves this.
 *
 * <p><b>Batch 8.2 stub.</b> This class provides the {@code RedisMessageListenerContainer} bean,
 * which the subscriber in Batch 8.4 uses to register channel listeners.
 *
 * <p><b>Channels:</b> notification-service publishes to channels named after the event domain:
 *
 * <ul>
 *   <li>{@code notifications:orders} — order events
 *   <li>{@code notifications:inventory} — inventory events
 *   <li>{@code notifications:payments} — payment events
 * </ul>
 *
 * <p>Each replica subscribes to all three.
 */
@Configuration
public class RedisConfig {

    /**
     * Container for Redis pub/sub listeners.
     *
     * <p>Spring Data Redis uses this container to dispatch incoming messages to registered {@code
     * MessageListener} instances. The container manages the underlying connection and thread pool.
     *
     * <p>In Batch 8.4, subscriber beans will register their channel listeners with this container.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}

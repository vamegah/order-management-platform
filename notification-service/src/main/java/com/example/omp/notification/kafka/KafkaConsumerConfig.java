package com.example.omp.notification.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Default Kafka consumer container factory for domain-event consumers (OrderEventConsumer,
 * PaymentEventConsumer, InventoryEventConsumer).
 *
 * <p>Uses MANUAL_IMMEDIATE ack mode (consumers call ack.acknowledge() after durable database
 * handling) and bounded retries followed by durable quarantine so a single bad record never blocks
 * a partition indefinitely.
 *
 * <p>The webhook pipeline has its own factories in WebhookKafkaConfig.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:latest}")
    private String autoOffsetReset;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Domain topic administrators must establish the partition count before consumption.
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 15_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 5_000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Named "kafkaListenerContainerFactory" — the default name Spring Kafka resolves when no
     * containerFactory is specified on @KafkaListener.
     *
     * <p>Error handling: 3 retries at 1-second intervals. On final failure the record is
     * quarantined before its offset can commit. A quarantine storage failure preserves the input
     * for retry.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            com.example.omp.notification.durable.EventQuarantine quarantine) {

        DefaultErrorHandler errorHandler = quarantine.handler("live", true);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}

package com.example.omp.notification.config;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;

/**
 * Kafka configuration for the outbound webhook pipeline.
 *
 * <p>Producers and consumers use String serialization; the payload is JSON (DeliveryMessage /
 * domain event envelope).
 */
@Configuration
public class WebhookKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${webhook.kafka.delivery-concurrency:6}")
    private int deliveryConcurrency;

    @Value("${webhook.kafka.event-concurrency:3}")
    private int eventConcurrency;

    @Value("${webhook.kafka.dlq-concurrency:1}")
    private int dlqConcurrency;

    @Value("${webhook.kafka.replication-factor:3}")
    private int replicationFactor;

    // -----------------------------------------------------------------------
    // Producer
    // -----------------------------------------------------------------------

    @Bean
    public ProducerFactory<String, String> webhookProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(webhookProducerFactory());
    }

    // -----------------------------------------------------------------------
    // Consumers
    // -----------------------------------------------------------------------

    private Map<String, Object> baseConsumerProps(String groupSuffix) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-" + groupSuffix);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Topic administrators own partition counts. Consumer auto-creation can create a
        // one-partition topic before the producer provisions it, leaving newly added
        // partitions unread until the next metadata refresh/rebalance.
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        return props;
    }

    private ConsumerFactory<String, String> consumerFactory(String groupSuffix) {
        return new DefaultKafkaConsumerFactory<>(baseConsumerProps(groupSuffix));
    }

    /**
     * Consumes domain events (orders/payments/inventory) and fans out to matching subscriptions.
     * Commit a record only after the listener returns successfully.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            webhookEventKafkaListenerContainerFactory() {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory("outbound-webhook"));
        factory.setConcurrency(eventConcurrency);
        factory.getContainerProperties()
                .setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * Consumes delivery messages (initial + retry topics). One thread per partition (concurrency
     * matches the partition count).
     *
     * <p>Note: the delivery worker may sleep up to the retry window (up to 24h). The container's
     * max.poll.interval is set accordingly.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            webhookDeliveryKafkaListenerContainerFactory() {

        Map<String, Object> props = baseConsumerProps("webhook-delivery");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 26 * 60 * 60 * 1000);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setConcurrency(deliveryConcurrency);
        factory.getContainerProperties()
                .setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            webhookDlqKafkaListenerContainerFactory() {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory("webhook-dlq"));
        factory.setConcurrency(dlqConcurrency);
        factory.getContainerProperties()
                .setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    // -----------------------------------------------------------------------
    // Topic creation (idempotent; safe to leave in place in prod if ACLs allow)
    // -----------------------------------------------------------------------

    @Bean
    public NewTopic webhookDeliveriesTopic() {
        return TopicBuilder.name(WebhookKafkaTopics.DELIVERIES)
                .partitions(6)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic webhookRetry1Topic() {
        return TopicBuilder.name(WebhookKafkaTopics.RETRY_1)
                .partitions(6)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic webhookRetry2Topic() {
        return TopicBuilder.name(WebhookKafkaTopics.RETRY_2)
                .partitions(6)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic webhookRetry3Topic() {
        return TopicBuilder.name(WebhookKafkaTopics.RETRY_3)
                .partitions(6)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic webhookRetry4Topic() {
        return TopicBuilder.name(WebhookKafkaTopics.RETRY_4)
                .partitions(6)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic webhookRetry5Topic() {
        return TopicBuilder.name(WebhookKafkaTopics.RETRY_5)
                .partitions(6)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic webhookDlqTopic() {
        return TopicBuilder.name(WebhookKafkaTopics.DLQ)
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }
}

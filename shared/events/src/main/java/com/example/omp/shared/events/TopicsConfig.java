package com.example.omp.shared.events;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic specifications.
 *
 * <p>Each topic is described by a {@link NewTopic} instance that the {@link TopicProvisioner} uses
 * to create it (if missing) at service startup.
 *
 * <p><b>Partition count and replication factor</b> are read from Spring properties so that dev and
 * prod can differ without code changes:
 *
 * <pre>
 *   omp.kafka.default-partitions: 3        (dev) / 6  (prod)
 *   omp.kafka.default-replication-factor: 1 (dev) / 3 (prod)
 *   omp.kafka.retention-hours: 168         (dev) / 720 (prod)
 * </pre>
 *
 * <p>Topic configs set here override Kafka's broker defaults:
 *
 * <ul>
 *   <li>{@code cleanup.policy=delete} — retention-based deletion (default). We don't use log
 *       compaction because we don't need "keep only the latest value per key" semantics.
 *   <li>{@code retention.ms} — how long to keep events.
 *   <li>{@code min.insync.replicas} — how many replicas must acknowledge a write before it's
 *       considered committed. Only meaningful when replication factor > 1.
 * </ul>
 */
public final class TopicsConfig {

    private TopicsConfig() {}

    /**
     * Builds the topic specifications for a given environment.
     *
     * @param partitions partition count (dev: 3, prod: 6)
     * @param replicationFactor replication factor (dev: 1, prod: 3)
     * @param retentionHours retention in hours (dev: 168 = 7 days, prod: 720 = 30 days)
     * @param minInsyncReplicas min.insync.replicas (dev: 1, prod: 2)
     */
    public static NewTopic[] forEnvironment(
            int partitions, int replicationFactor, int retentionHours, int minInsyncReplicas) {
        if (partitions < 1
                || replicationFactor < 1
                || replicationFactor > Short.MAX_VALUE
                || retentionHours < 1
                || minInsyncReplicas < 1
                || minInsyncReplicas > replicationFactor) {
            throw new IllegalArgumentException(
                    "Invalid Kafka topic partitions, replicas, retention or minimum ISR");
        }

        Map<String, String> commonConfigs =
                Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG,
                                String.valueOf(retentionHours * 60L * 60L * 1000L),
                        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInsyncReplicas));

        return new NewTopic[] {
            topic(KafkaTopics.ORDERS_EVENTS_ORDER, partitions, replicationFactor, commonConfigs),
            topic(
                    KafkaTopics.INVENTORY_EVENTS_RESERVATION,
                    partitions,
                    replicationFactor,
                    commonConfigs),
            topic(KafkaTopics.PAYMENTS_EVENTS_PAYMENT, partitions, replicationFactor, commonConfigs)
        };
    }

    private static NewTopic topic(
            String name, int partitions, int replicationFactor, Map<String, String> configs) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .configs(configs)
                .build();
    }
}

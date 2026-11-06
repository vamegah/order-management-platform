package com.example.omp.shared.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/** Declares domain topics for Boot's configured KafkaAdmin, including its TLS/SASL settings. */
@AutoConfiguration(
        afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaAdmin.class)
@ConditionalOnBean(KafkaAdmin.class)
@ConditionalOnProperty(
        name = "omp.kafka.provision-topics",
        havingValue = "true",
        matchIfMissing = true)
public class TopicProvisioner {
    @Bean("ompDomainTopics")
    @ConditionalOnMissingBean(name = "ompDomainTopics")
    public KafkaAdmin.NewTopics domainTopics(
            @Value("${omp.kafka.default-partitions:3}") int partitions,
            @Value("${omp.kafka.default-replication-factor:1}") int replicationFactor,
            @Value("${omp.kafka.retention-hours:168}") int retentionHours,
            @Value("${omp.kafka.min-insync-replicas:1}") int minInsyncReplicas) {
        return new KafkaAdmin.NewTopics(
                TopicsConfig.forEnvironment(
                        partitions, replicationFactor, retentionHours, minInsyncReplicas));
    }
}

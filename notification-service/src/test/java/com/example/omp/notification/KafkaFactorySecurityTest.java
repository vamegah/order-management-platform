package com.example.omp.notification;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.notification.config.WebhookKafkaConfig;
import com.example.omp.notification.kafka.KafkaConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaFactorySecurityTest {
    @Test
    void domainAndWebhookFactoriesRetainManagedAuthenticationAndTransportSettings() {
        var properties = new KafkaProperties();
        properties.getProperties().put("security.protocol", "SASL_SSL");
        properties.getProperties().put("sasl.mechanism", "SCRAM-SHA-512");
        properties.getProperties().put("ssl.truststore.location", "/run/secrets/kafka-truststore");
        var domain = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(domain, "bootstrapServers", "broker:9092");
        ReflectionTestUtils.setField(domain, "groupId", "notification");
        ReflectionTestUtils.setField(domain, "autoOffsetReset", "earliest");
        domain.configureConnectionProperties(properties);
        assertThat(domain.consumerFactory().getConfigurationProperties())
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("ssl.truststore.location", "/run/secrets/kafka-truststore");
        var webhook = new WebhookKafkaConfig();
        ReflectionTestUtils.setField(webhook, "bootstrapServers", "broker:9092");
        webhook.configureConnectionProperties(properties);
        assertThat(webhook.webhookProducerFactory().getConfigurationProperties())
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("ssl.truststore.location", "/run/secrets/kafka-truststore");
    }
}

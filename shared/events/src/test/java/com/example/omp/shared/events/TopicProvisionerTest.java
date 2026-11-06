package com.example.omp.shared.events;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;

class TopicProvisionerTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TopicProvisioner.class));

    @Test
    void provisionerRequiresAnAdminAndHonorsOptOut() {
        runner.run(context -> assertThat(context).doesNotHaveBean(KafkaAdmin.NewTopics.class));
        runner.withBean(KafkaAdmin.class, TopicProvisionerTest::admin)
                .withPropertyValues("omp.kafka.provision-topics=false")
                .run(context -> assertThat(context).doesNotHaveBean(KafkaAdmin.NewTopics.class));
    }

    private static KafkaAdmin admin() {
        var admin =
                new KafkaAdmin(
                        Map.of(
                                "bootstrap.servers",
                                "example.invalid:9093",
                                "security.protocol",
                                "SASL_SSL",
                                "sasl.mechanism",
                                "PLAIN"));
        admin.setAutoCreate(false); // Context contract test; no broker required.
        return admin;
    }

    @Test
    void usesTheConfiguredAdminAndDeclaresAllThreeTopics() {
        var admin = admin();
        runner.withBean(KafkaAdmin.class, () -> admin)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(KafkaAdmin.NewTopics.class);
                            assertThat(context.getBean(KafkaAdmin.class)).isSameAs(admin);
                            assertThat(admin.getConfigurationProperties())
                                    .containsEntry("security.protocol", "SASL_SSL");
                        });
        var topics = TopicsConfig.forEnvironment(6, 3, 720, 2);
        assertThat(topics)
                .extracting(org.apache.kafka.clients.admin.NewTopic::name)
                .containsExactly(
                        KafkaTopics.ORDERS_EVENTS_ORDER,
                        KafkaTopics.INVENTORY_EVENTS_RESERVATION,
                        KafkaTopics.PAYMENTS_EVENTS_PAYMENT);
        for (var topic : topics) {
            assertThat(topic.numPartitions()).isEqualTo(6);
            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
            assertThat(topic.configs())
                    .containsEntry("retention.ms", "2592000000")
                    .containsEntry("min.insync.replicas", "2")
                    .containsEntry("cleanup.policy", "delete");
        }
    }

    @ParameterizedTest
    @CsvSource({"0,1,24,1", "3,0,24,1", "3,32768,24,1", "3,1,0,1", "3,1,24,0", "3,1,24,2"})
    void rejectsInvalidTopicSettings(int partitions, int replicas, int retention, int minimumIsr) {
        assertThatThrownBy(
                        () ->
                                TopicsConfig.forEnvironment(
                                        partitions, replicas, retention, minimumIsr))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.example.omp.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaTestHelper {
    private KafkaTestHelper() {}

    public static void createTopic(String bootstrap, String name, int partitions) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            if (!admin.listTopics().names().get().contains(name)) {
                admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
            }
        }
    }

    public static List<String> drain(String bootstrap, String topic, Duration timeout) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("bootstrap.servers", bootstrap);
        properties.put("group.id", "assert-" + UUID.randomUUID());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", StringDeserializer.class);
        properties.put("value.deserializer", StringDeserializer.class);
        List<String> messages = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200))
                        .forEach(record -> messages.add(record.value()));
            }
        }
        return messages;
    }

    /** Wait for processing and commits, including messages generated on retry topics. */
    public static void awaitWebhookConsumersCaughtUp(String bootstrap, String groupPrefix) {
        List<String> sources =
                List.of(
                        "payments.events.payment",
                        "orders.events.order",
                        "inventory.events.reservation");
        // Use the production constants so a renamed retry topic cannot evade the barrier.
        List<String> deliveries =
                List.of(
                        com.example.omp.notification.webhook.config.WebhookKafkaTopics.DELIVERIES,
                        com.example.omp.notification.webhook.config.WebhookKafkaTopics.RETRY_1,
                        com.example.omp.notification.webhook.config.WebhookKafkaTopics.RETRY_2,
                        com.example.omp.notification.webhook.config.WebhookKafkaTopics.RETRY_3,
                        com.example.omp.notification.webhook.config.WebhookKafkaTopics.RETRY_4,
                        com.example.omp.notification.webhook.config.WebhookKafkaTopics.RETRY_5);
        Map<String, List<String>> groups =
                Map.of(
                        groupPrefix + "-outbound-webhook", sources,
                        groupPrefix + "-webhook-delivery", deliveries,
                        groupPrefix + "-webhook-dlq",
                                List.of(
                                        com.example.omp.notification.webhook.config
                                                .WebhookKafkaTopics.DLQ));
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            await().atMost(Duration.ofSeconds(40))
                    .during(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                for (var entry : groups.entrySet()) {
                                    var descriptions =
                                            admin.describeTopics(entry.getValue())
                                                    .allTopicNames()
                                                    .get();
                                    Map<TopicPartition, OffsetSpec> requested = new HashMap<>();
                                    descriptions.forEach(
                                            (topic, description) ->
                                                    description
                                                            .partitions()
                                                            .forEach(
                                                                    partition ->
                                                                            requested.put(
                                                                                    new TopicPartition(
                                                                                            topic,
                                                                                            partition
                                                                                                    .partition()),
                                                                                    OffsetSpec
                                                                                            .latest())));
                                    var ends = admin.listOffsets(requested).all().get();
                                    var committed =
                                            admin.listConsumerGroupOffsets(entry.getKey())
                                                    .partitionsToOffsetAndMetadata()
                                                    .get();
                                    ends.forEach(
                                            (partition, end) ->
                                                    assertThat(
                                                                    committed.containsKey(partition)
                                                                            ? committed
                                                                                    .get(partition)
                                                                                    .offset()
                                                                            : 0L)
                                                            .as(
                                                                    "Committed offset for %s in %s",
                                                                    partition, entry.getKey())
                                                            .isEqualTo(end.offset()));
                                }
                            });
        }
    }
}

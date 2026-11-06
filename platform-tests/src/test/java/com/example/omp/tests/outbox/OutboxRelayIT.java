package com.example.omp.tests.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.payment.PaymentServiceApplication;
import com.example.omp.shared.events.outbox.OutboxDrainer;
import com.example.omp.testsupport.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Exercises the production outbox drainer with Postgres and Kafka. */
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        properties = {
            "spring.config.location=file:payment-service/src/main/resources/application.yml,classpath:application-test.yml",
            "grpc.server.security.enabled=false",
            "grpc.server.port=0",
            "server.port=0",
            "management.server.port=0",
            "omp.outbox.poll-interval=1h"
        })
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayIT {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ServiceTestProperties.register(registry, "payment-service", "payments");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDrainer drainer;

    @BeforeEach
    void clean() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo("payments");
        jdbc.execute("TRUNCATE payments.outbox_event");
        KafkaTestHelper.createTopic(SharedContainers.kafkaBootstrap(), "outbox.events", 1);
    }

    private UUID insert(String marker) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            INSERT INTO payments.outbox_event(id,aggregate_type,aggregate_id,event_type,topic,payload)
            VALUES (?, 'PAYMENT', ?, 'payment.confirmed', 'outbox.events', ?::jsonb)
            """,
                id,
                UUID.randomUUID().toString(),
                "{\"eventId\":\"" + marker + "\",\"eventType\":\"payment.confirmed\"}");
        return id;
    }

    @Test
    void acknowledgedPublishMarksRowAndDoesNotRepublishIt() {
        String marker = UUID.randomUUID().toString();
        UUID id = insert(marker);
        drainer.drain();
        drainer.drain();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payments.outbox_event WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("PUBLISHED");
        assertThat(
                        KafkaTestHelper.drain(
                                        SharedContainers.kafkaBootstrap(),
                                        "outbox.events",
                                        Duration.ofSeconds(5))
                                .stream()
                                .filter(message -> message.contains(marker))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void pendingRowCanBeReplayedAfterPublishBeforeAcknowledgement() {
        String marker = UUID.randomUUID().toString();
        UUID id = insert(marker);
        drainer.drain();
        jdbc.update(
                "UPDATE payments.outbox_event SET status='PENDING',published_at=NULL WHERE id=?",
                id);
        drainer.drain();
        assertThat(
                        KafkaTestHelper.drain(
                                        SharedContainers.kafkaBootstrap(),
                                        "outbox.events",
                                        Duration.ofSeconds(5))
                                .stream()
                                .filter(message -> message.contains(marker))
                                .count())
                .isEqualTo(2);
    }

    @Test
    void emptyDrainLeavesDatabaseEmpty() {
        drainer.drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.outbox_event", Integer.class))
                .isZero();
    }
}

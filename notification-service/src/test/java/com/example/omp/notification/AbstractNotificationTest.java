package com.example.omp.notification;

import com.example.omp.notification.support.TestSecurityConfig;
import com.example.omp.shared.events.KafkaTopics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Full application tests with real PostgreSQL/Redis, embedded Kafka and a test JWT decoder. Spring
 * owns container lifetimes; each class closes its context before stopping infrastructure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, AbstractNotificationTest.Infrastructure.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(
        partitions = 1,
        topics = {
            KafkaTopics.ORDERS_EVENTS_ORDER,
            KafkaTopics.PAYMENTS_EVENTS_PAYMENT,
            KafkaTopics.INVENTORY_EVENTS_RESERVATION
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class AbstractNotificationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("omp")
                    .withUsername("notifications_svc")
                    .withPassword("test")
                    .withUrlParam("currentSchema", "notifications,public");
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);
        }
    }
}

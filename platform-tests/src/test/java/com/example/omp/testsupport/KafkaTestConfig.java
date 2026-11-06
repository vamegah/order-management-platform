package com.example.omp.testsupport;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Creates the outbox and webhook topics the tests expect, on top of whatever the application
 * creates.
 *
 * <p>This is deliberately redundant with the applications' topic beans: it lets a test that boots
 * only one service still have every topic it consumes from.
 */
@TestConfiguration
public class KafkaTestConfig {

    @Bean
    public NewTopic outboxTopic() {
        return new NewTopic("outbox.events", 3, (short) 1);
    }

    @Bean
    public NewTopic paymentsEventsTopic() {
        return new NewTopic("payments.events.payment", 3, (short) 1);
    }

    @Bean
    public NewTopic ordersEventsTopic() {
        return new NewTopic("orders.events.order", 3, (short) 1);
    }

    @Bean
    public NewTopic inventoryEventsTopic() {
        return new NewTopic("inventory.events.reservation", 3, (short) 1);
    }

    @Bean
    public NewTopic inboundWebhooksTopic() {
        return new NewTopic("webhooks.inbound.received", 3, (short) 1);
    }
}

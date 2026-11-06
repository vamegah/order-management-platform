package com.example.omp.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Main entry point for the notification service.
 *
 * <p>Real-time push service. Consumes Kafka events from orders, inventory, and payments, then
 * forwards them to subscribed WebSocket clients.
 *
 * <p><b>Endpoints:</b>
 *
 * <ul>
 *   <li>{@code /ws} — STOMP over WebSocket handshake endpoint (port 8084).
 *   <li>{@code /actuator/health}, {@code /actuator/prometheus}, etc. — management HTTP (port 8085).
 * </ul>
 *
 * <p><b>Data flow:</b>
 *
 * <ol>
 *   <li>Kafka topics receive events from order-service, inventory-service, payment-service.
 *   <li>{@code @KafkaListener} consumers in this service receive each event.
 *   <li>Consumers publish to a Redis channel, so all replicas see the event.
 *   <li>Each replica's Redis subscriber pushes the event to its local WebSocket clients whose
 *       subscriptions match.
 * </ol>
 *
 * <p>WebSocket subscriptions are held in memory and tied to connection lifetimes. Redis supplies
 * live fan-out. Outbound webhook subscriptions and delivery state use PostgreSQL.
 *
 * <p><b>Subscriptions:</b> clients connect via STOMP, then subscribe to topics like {@code
 * /topic/orders/{orderId}}. The service maintains an in-memory registry of active subscriptions,
 * filtered against events. Details in Batch 8.3.
 */
@SpringBootApplication
@org.springframework.context.annotation.Import({
    com.example.omp.shared.events.DomainEventReader.class,
    com.example.omp.shared.events.EventRegistry.class
})
@EnableKafka
@ConfigurationPropertiesScan
@EnableFeignClients
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

package com.example.omp.notification.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Structured metrics for the event processing pipeline.
 *
 * <p>Complements the raw counters incremented inline in consumers and dispatchers. This class
 * exists to centralize metric names and help ensure consistency.
 *
 * <p><b>Pipeline stages instrumented:</b>
 *
 * <ol>
 *   <li>Kafka consumption (per topic, per event type) — in the consumers.
 *   <li>Redis publish (per channel) — in {@code RedisEventPublisher}.
 *   <li>Redis receive (per channel) — in {@code RedisEventSubscriber}.
 *   <li>Dispatch to WebSocket (per destination type) — in {@code EventDispatcherImpl}.
 * </ol>
 *
 * <p><b>Pipeline health at a glance:</b> Compare {@code kafka.events.consumed} rate to {@code
 * websocket.events.dispatched} rate. If Kafka consumption exceeds dispatch, something is failing or
 * no clients are subscribed.
 */
@Component
public class EventPipelineMetrics {

    public EventPipelineMetrics(MeterRegistry registry) {
        // Eagerly register the metrics so they appear in Prometheus output
        // even before the first event.
        // (Counters with `.count()` never expire; they just start at 0.)
        registry.counter("websocket.events.dispatched", "destination_type", "ORDERS");
        registry.counter("websocket.events.dispatched", "destination_type", "INVENTORY");
        registry.counter("websocket.events.dispatched", "destination_type", "PAYMENTS");
    }
}

package com.example.omp.notification.websocket;

import com.example.omp.notification.subscription.SubscriptionRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Custom Micrometer metrics for WebSocket behavior.
 *
 * <p>Standard Spring metrics don't include WebSocket connection counts or subscription counts.
 * These custom metrics fill that gap.
 *
 * <p><b>Metrics exposed:</b>
 *
 * <ul>
 *   <li>{@code websocket_connections_active} — gauge (current count)
 *   <li>{@code websocket_connections_total} — counter (monotonic, since start)
 *   <li>{@code websocket_disconnects_total} — counter
 *   <li>{@code websocket_subscriptions_active} — gauge (from the registry)
 * </ul>
 *
 * <p>These are exposed on the management HTTP port (8085) at {@code /actuator/prometheus}.
 */
@Component
public class WebSocketMetrics {

    private final MeterRegistry registry;
    private final SubscriptionRegistry subscriptionRegistry;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger totalDisconnects = new AtomicInteger(0);

    public WebSocketMetrics(MeterRegistry registry, SubscriptionRegistry subscriptionRegistry) {
        this.registry = registry;
        this.subscriptionRegistry = subscriptionRegistry;
    }

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("websocket.connections.active", activeConnections, AtomicInteger::get)
                .description("Current number of active WebSocket connections")
                .register(registry);

        Gauge.builder(
                        "websocket.subscriptions.active",
                        subscriptionRegistry,
                        SubscriptionRegistry::totalSubscriptions)
                .description("Current number of active subscriptions")
                .register(registry);

        Gauge.builder(
                        "websocket.sessions.active",
                        subscriptionRegistry,
                        SubscriptionRegistry::totalSessions)
                .description("Current number of sessions with subscriptions")
                .register(registry);
    }

    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        registry.counter("websocket.connections.total").increment();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        activeConnections.decrementAndGet();
        totalDisconnects.incrementAndGet();
        registry.counter("websocket.disconnects.total").increment();
    }
}

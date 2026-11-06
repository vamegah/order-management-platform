package com.example.omp.notification.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Instruments WebSocket backpressure events.
 *
 * <p>Spring closes WebSocket sessions when the outbound buffer exceeds the configured limit (see
 * WebSocketConfig). This class counts those events so operators can alert on abnormal disconnect
 * rates.
 *
 * <p><b>Detection:</b> disconnect events with a specific close status (e.g., {@code
 * SESSION_NOT_RELIABLE} or {@code POLICY_VIOLATION}) are classified as backpressure-related.
 */
@Component
public class BackpressureMetrics {

    private static final Logger log = LoggerFactory.getLogger(BackpressureMetrics.class);

    private final MeterRegistry registry;

    public BackpressureMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.counter("websocket.disconnects.total", "reason", "unknown");
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        var status = event.getCloseStatus();

        String reason = classify(status);
        registry.counter("websocket.disconnects.total", "reason", reason).increment();

        if ("policy_violation".equals(reason) || "buffer_overflow".equals(reason)) {
            log.warn(
                    "WebSocket session closed due to backpressure: sessionId={} reason={}",
                    event.getSessionId(),
                    reason);
        }
    }

    private static String classify(org.springframework.web.socket.CloseStatus status) {
        if (status == null) return "unknown";
        return switch (status.getCode()) {
            case 1000 -> "normal";
            case 1001 -> "going_away";
            case 1006 -> "abnormal";
            case 1008 -> "policy_violation";
            default -> "other";
        };
    }
}

package com.example.omp.notification.webhook.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for the synthetic webhook.test event. Subscribers use this to validate their endpoint
 * configuration end to end.
 */
public final class WebhookTestEvent {

    public static final String EVENT_TYPE = "webhook.test";

    private WebhookTestEvent() {}

    public static Map<String, Object> payload(UUID subscriptionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriptionId", subscriptionId.toString());
        m.put("message", "This is a test event from the notification-service.");
        m.put("issuedAt", Instant.now().toString());
        return m;
    }
}

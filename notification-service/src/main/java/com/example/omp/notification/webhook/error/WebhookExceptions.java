package com.example.omp.notification.webhook.error;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

public final class WebhookExceptions {

    private WebhookExceptions() {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class SubscriptionNotFound extends RuntimeException {
        public SubscriptionNotFound(UUID id) {
            super("Webhook subscription not found: " + id);
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class SubscriptionDisabled extends RuntimeException {
        public SubscriptionDisabled(UUID id) {
            super("Webhook subscription is disabled: " + id);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidSubscription extends RuntimeException {
        public InvalidSubscription(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class DlqEntryNotFound extends IllegalArgumentException {
        public DlqEntryNotFound(UUID deliveryId) {
            super("No DLQ entry for delivery: " + deliveryId);
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class DlqAlreadyReplayed extends IllegalStateException {
        public DlqAlreadyReplayed(UUID deliveryId) {
            super("DLQ delivery was already replayed: " + deliveryId);
        }
    }
}

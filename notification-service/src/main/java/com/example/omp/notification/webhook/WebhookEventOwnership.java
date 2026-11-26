package com.example.omp.notification.webhook;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Event types select interests; only a verified administrator may receive another customer's data.
 */
public final class WebhookEventOwnership {
    private WebhookEventOwnership() {}

    public static boolean allows(WebhookSubscription subscription, JsonNode event) {
        if (subscription.getOwnerType() == SubscriptionOwnerType.ADMIN) return true;
        if (subscription.getOwnerType() != SubscriptionOwnerType.CUSTOMER
                || subscription.getOwnerId() == null
                || event == null) return false;
        JsonNode customer = event.get("customerId");
        return customer != null
                && customer.isTextual()
                && subscription.getOwnerId().toString().equals(customer.textValue());
    }

    public static final class Denied extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Denied() {
            super("Webhook event ownership denied");
        }
    }
}

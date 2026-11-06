package com.example.omp.notification.webhook.matcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Published dotted subscription names and their canonical producer event types. */
public final class WebhookEventTypes {
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> aliases = new HashMap<>();
        Map.ofEntries(
                        Map.entry("ORDER_PLACED", "order.placed"),
                        Map.entry("ORDER_SHIPPED", "order.shipped"),
                        Map.entry("ORDER_CANCELLED", "order.cancelled"),
                        Map.entry("RESERVATION_CREATED", "inventory.reserved"),
                        Map.entry("RESERVATION_RELEASED", "inventory.released"),
                        Map.entry("PAYMENT_INTENT_CREATED", "payment.initiated"),
                        Map.entry("PAYMENT_CONFIRMED", "payment.confirmed"),
                        Map.entry("PAYMENT_FAILED", "payment.failed"),
                        Map.entry("PAYMENT_REFUNDED", "payment.refunded"))
                .forEach(
                        (canonical, alias) -> {
                            aliases.put(canonical, alias);
                            aliases.put(alias, canonical);
                        });
        ALIASES = Map.copyOf(aliases);
    }

    private WebhookEventTypes() {}

    /** Prefer the original type when a subscription lists both equivalent spellings. */
    public static List<String> matchingTypes(String eventType) {
        String alias = ALIASES.get(eventType);
        return alias == null ? List.of(eventType) : List.of(eventType, alias);
    }
}

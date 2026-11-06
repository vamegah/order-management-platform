package com.example.omp.notification.subscription;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The kinds of topics clients can subscribe to.
 *
 * <p>Each type maps a destination pattern to a resource ID:
 *
 * <ul>
 *   <li>{@code /topic/orders/{orderId}} → ORDERS
 *   <li>{@code /topic/inventory/{sku}} → INVENTORY
 *   <li>{@code /topic/payments/{paymentId}} → PAYMENTS
 * </ul>
 *
 * <p>{@code /user/queue/notifications} receives only the authenticated customer's order/payment
 * events.
 */
public enum TopicType {
    ORDERS("^/topic/orders/([a-f0-9\\-]{36})$"),
    INVENTORY("^/topic/inventory/([A-Z0-9_-]{1,64})$"),
    PAYMENTS("^/topic/payments/([a-f0-9\\-]{36})$"),
    NOTIFICATIONS("^/user/queue/(notifications)$");

    private final Pattern pattern;

    TopicType(String pattern) {
        this.pattern = Pattern.compile(pattern);
    }

    /**
     * Parses a destination and returns the matching type plus the resource ID. Returns empty if the
     * destination doesn't match any known topic.
     */
    public static Optional<ParsedDestination> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        for (TopicType type : values()) {
            var matcher = type.pattern.matcher(destination);
            if (matcher.matches()) {
                return Optional.of(new ParsedDestination(type, matcher.group(1)));
            }
        }
        return Optional.empty();
    }

    public record ParsedDestination(TopicType type, String resourceId) {}
}

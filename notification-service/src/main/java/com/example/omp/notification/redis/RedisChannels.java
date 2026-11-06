package com.example.omp.notification.redis;

/**
 * Canonical Redis pub/sub channel names.
 *
 * <p>One channel per event domain. All notification-service replicas subscribe to all three
 * channels.
 */
public final class RedisChannels {

    private RedisChannels() {
        // Constants only.
    }

    public static final String ORDERS = "notifications:orders";
    public static final String INVENTORY = "notifications:inventory";
    public static final String PAYMENTS = "notifications:payments";

    public static final String[] ALL = {ORDERS, INVENTORY, PAYMENTS};
}

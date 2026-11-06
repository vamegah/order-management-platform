package com.example.omp.notification.webhook;

import java.util.Locale;

public enum SubscriptionOwnerType {
    CUSTOMER,
    ADMIN;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SubscriptionOwnerType fromDb(String value) {
        return SubscriptionOwnerType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

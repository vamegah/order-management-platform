package com.example.omp.shared.events;

import java.util.Set;

public final class PaymentEventType {

    private PaymentEventType() {}

    public static final String PAYMENT_INTENT_CREATED = "PAYMENT_INTENT_CREATED";
    public static final String PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED";
    public static final String PAYMENT_CANCELLED = "PAYMENT_CANCELLED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String PAYMENT_PARTIALLY_REFUNDED = "PAYMENT_PARTIALLY_REFUNDED";

    public static final Set<String> ALL =
            Set.of(
                    PAYMENT_INTENT_CREATED,
                    PAYMENT_CONFIRMED,
                    PAYMENT_CANCELLED,
                    PAYMENT_FAILED,
                    PAYMENT_REFUNDED,
                    PAYMENT_PARTIALLY_REFUNDED);
}

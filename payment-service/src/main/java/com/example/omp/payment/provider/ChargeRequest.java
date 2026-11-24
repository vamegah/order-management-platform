package com.example.omp.payment.provider;

import java.util.UUID;

public record ChargeRequest(
        UUID paymentId,
        UUID orderId,
        UUID customerId,
        long amountMinor,
        String currency,
        String idempotencyKey,
        String description,
        String paymentMethodId,
        String providerReference) {
    public ChargeRequest(
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            long amountMinor,
            String currency,
            String idempotencyKey,
            String description) {
        this(
                paymentId,
                orderId,
                customerId,
                amountMinor,
                currency,
                idempotencyKey,
                description,
                null,
                null);
    }
}

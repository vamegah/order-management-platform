package com.example.omp.payment.provider;

import java.util.UUID;

public record RefundRequest(
        UUID paymentId,
        String providerReference,
        long amountMinor,
        String currency,
        String idempotencyKey,
        String reason) {}

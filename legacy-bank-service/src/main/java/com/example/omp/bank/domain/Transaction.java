package com.example.omp.bank.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Transaction(
        String id,
        String fromAccount,
        String toAccount,
        BigDecimal amount,
        String currency,
        String reference,
        Instant timestamp,
        TransactionStatus status) {
    public enum TransactionStatus {
        COMPLETED,
        REVERSED
    }
}

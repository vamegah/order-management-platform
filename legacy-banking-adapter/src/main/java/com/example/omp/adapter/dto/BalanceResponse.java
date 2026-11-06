package com.example.omp.adapter.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(
        String accountNumber, BigDecimal balance, String currency, Instant timestamp) {}

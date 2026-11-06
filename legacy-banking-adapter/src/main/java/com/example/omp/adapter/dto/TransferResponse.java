package com.example.omp.adapter.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        String transactionId, String status, Instant timestamp, BigDecimal newBalance) {}

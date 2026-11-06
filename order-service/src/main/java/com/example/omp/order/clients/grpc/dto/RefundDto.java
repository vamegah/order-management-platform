package com.example.omp.order.clients.grpc.dto;

import java.util.UUID;

public record RefundDto(UUID id, UUID paymentId, String status) {}

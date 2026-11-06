package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Paginated order list, mirroring the response of {@code GET /api/v1/orders}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPageDto(
        List<OrderClientDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {}

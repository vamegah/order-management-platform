package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Paginated customer list, mirroring the response of {@code GET /api/v1/customers}.
 *
 * <p>Shape matches the shared {@code PageResponse} envelope used by all three REST services. Kept
 * as its own record per service so that changes to one service's pagination don't affect another's
 * deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerPageDto(
        List<CustomerClientDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {}

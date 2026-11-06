package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPageDto(
        List<ProductClientDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {}

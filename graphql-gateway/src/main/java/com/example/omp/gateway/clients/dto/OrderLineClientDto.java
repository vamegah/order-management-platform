package com.example.omp.gateway.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirror of order-service's {@code OrderLineDto}.
 *
 * <p>Snapshot fields (product SKU, name, unit price) reflect the values captured at order time, not
 * the product's current values. The {@code productId} is used by the gateway's {@code
 * OrderLine.product} field resolver to fetch the live product.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLineClientDto(
        UUID id,
        UUID productId,
        String productSku,
        String productName,
        MoneyDto unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoneyDto(BigDecimal amount, String currency) {}
}

package com.example.omp.order.service;

import com.example.omp.order.domain.OrderLine;
import com.example.omp.order.dto.OrderLineDto;
import com.example.omp.shared.domain.Money;
import java.util.UUID;

public final class OrderLineMapper {

    private OrderLineMapper() {}

    /** Builds a line from a product snapshot (already fetched from product-service). */
    public static OrderLine fromProduct(
            UUID productId, String sku, String name, Money unitPrice, int quantity) {
        return new OrderLine(productId, sku, name, unitPrice, quantity);
    }

    public static OrderLineDto toDto(OrderLine line) {
        return OrderMapper.toLineDto(line);
    }
}

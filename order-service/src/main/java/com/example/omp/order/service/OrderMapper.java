package com.example.omp.order.service;

import com.example.omp.order.domain.*;
import com.example.omp.order.dto.*;
import com.example.omp.shared.domain.Money;
import java.util.List;

public final class OrderMapper {

    private OrderMapper() {}

    /** Converts an order to its full DTO, without history. */
    public static OrderDto toDto(Order order) {
        if (order == null) return null;
        return toDto(order, false);
    }

    /** Converts an order to its full DTO, optionally including history. */
    public static OrderDto toDto(Order order, boolean includeHistory) {
        if (order == null) return null;

        List<OrderLineDto> lines = order.getLines().stream().map(OrderMapper::toLineDto).toList();

        List<OrderDto.OrderStatusHistoryDto> history =
                includeHistory
                        ? order.getStatusHistory().stream().map(OrderMapper::toHistoryDto).toList()
                        : null;

        return new OrderDto(
                order.getId(),
                order.getOrderNumber() != null ? order.getOrderNumber().value() : null,
                order.getCustomerId(),
                order.getCustomerEmail(),
                order.getStatus().name(),
                money(order.getSubtotal()),
                money(order.getTax()),
                money(order.getShipping()),
                money(order.getTotal()),
                shipping(order.getShippingAddress()),
                lines,
                history,
                order.getPlacedAt(),
                order.getNotes(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getPaymentId());
    }

    public static OrderSummaryDto toSummary(Order order) {
        if (order == null) return null;
        return new OrderSummaryDto(
                order.getId(),
                order.getOrderNumber() != null ? order.getOrderNumber().value() : null,
                order.getStatus().name(),
                order.getTotal().getAmount(),
                order.getTotal().getCurrencyCode(),
                order.getPlacedAt());
    }

    public static OrderLineDto toLineDto(OrderLine line) {
        if (line == null) return null;
        return new OrderLineDto(
                line.getId(),
                line.getProductId(),
                line.getProductSku(),
                line.getProductName(),
                new OrderLineDto.MoneyDto(
                        line.getUnitPrice().getAmount(), line.getUnitPrice().getCurrencyCode()),
                line.getQuantity(),
                line.getLineTotal(),
                line.getReservationId());
    }

    public static OrderDto.OrderStatusHistoryDto toHistoryDto(OrderStatusHistory h) {
        if (h == null) return null;
        return new OrderDto.OrderStatusHistoryDto(
                h.getFromStatus() != null ? h.getFromStatus().name() : null,
                h.getToStatus().name(),
                h.getReason(),
                h.getChangedBy(),
                h.getChangedAt());
    }

    private static OrderDto.MoneyDto money(Money m) {
        return m == null ? null : new OrderDto.MoneyDto(m.getAmount(), m.getCurrencyCode());
    }

    private static OrderDto.ShippingAddressDto shipping(ShippingAddress s) {
        if (s == null) return null;
        return new OrderDto.ShippingAddressDto(
                s.getLine1(),
                s.getLine2(),
                s.getCity(),
                s.getState(),
                s.getPostalCode(),
                s.getCountry());
    }
}

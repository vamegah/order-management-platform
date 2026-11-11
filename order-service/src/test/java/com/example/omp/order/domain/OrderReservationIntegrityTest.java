package com.example.omp.order.domain;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.shared.domain.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderReservationIntegrityTest {
    @ParameterizedTest
    @CsvSource({
        "DRAFT,EMPTY", "DRAFT,MISSING", "DRAFT,SHARED", "DRAFT,OVERSIZED",
        "PLACED,EMPTY", "PLACED,MISSING", "PLACED,SHARED", "PLACED,OVERSIZED",
        "CONFIRMED,EMPTY", "CONFIRMED,MISSING", "CONFIRMED,SHARED", "CONFIRMED,OVERSIZED",
        "FULFILLING,EMPTY", "FULFILLING,MISSING", "FULFILLING,SHARED", "FULFILLING,OVERSIZED",
        "SHIPPED,EMPTY", "SHIPPED,MISSING", "SHIPPED,SHARED", "SHIPPED,OVERSIZED"
    })
    void forwardTransitionsRejectInvalidPersistedReferencesWithoutChangingTheAggregate(
            OrderStatus initial, String fault) {
        var order = validOrder(1);
        advanceTo(order, initial);
        // Simulate legacy/corrupt data hydrated by JPA, bypassing public line mutation rules.
        var lines = new ArrayList<>(order.getLines());
        switch (fault) {
            case "EMPTY" -> lines.clear();
            case "MISSING" -> ReflectionTestUtils.setField(lines.getFirst(), "reservationId", null);
            case "SHARED" -> {
                var duplicate = line();
                ReflectionTestUtils.setField(
                        duplicate, "reservationId", lines.getFirst().getReservationId());
                lines.add(duplicate);
            }
            case "OVERSIZED" -> {
                while (lines.size() < 101) lines.add(line());
            }
            default -> throw new AssertionError(fault);
        }
        ReflectionTestUtils.setField(order, "lines", lines);
        var history = List.copyOf(order.getStatusHistory());
        var total = order.getTotal();
        var placed = order.getPlacedAt();
        var references = lines.stream().map(OrderLine::getReservationId).toList();

        assertThatThrownBy(() -> forward(order)).isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(initial);
        assertThat(order.getStatusHistory()).containsExactlyElementsOf(history);
        assertThat(order.getPlacedAt()).isEqualTo(placed);
        assertThat(order.getTotal()).isEqualTo(total);
        assertThat(order.getLines()).containsExactlyElementsOf(lines);
        assertThat(order.getLines().stream().map(OrderLine::getReservationId).toList())
                .containsExactlyElementsOf(references);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void validBoundaryPlansCompleteEveryForwardTransition(int count) {
        var order = validOrder(count);
        advanceTo(order, OrderStatus.SHIPPED);
        order.deliver("carrier", "Delivered");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getStatusHistory()).hasSize(6);
        assertThat(order.getLines()).hasSize(count);
        assertThat(order.getLines().stream().map(OrderLine::getReservationId).distinct().count())
                .isEqualTo(count);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"DRAFT", "PLACED", "CONFIRMED"})
    void cancellationRemainsAvailableForHistoricalOrdersWithoutReferences(OrderStatus initial) {
        var order = validOrder(1);
        advanceTo(order, initial);
        ReflectionTestUtils.setField(order.getLines().getFirst(), "reservationId", null);
        int history = order.getStatusHistory().size();
        order.cancel("operator", "Investigated historical order");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory()).hasSize(history + 1);
        assertThat(order.getLines().getFirst().getReservationId()).isNull();
    }

    private Order validOrder(int count) {
        var order =
                new Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "customer@example.com",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        for (int index = 0; index < count; index++) order.addLine(line());
        return order;
    }

    private OrderLine line() {
        var line =
                new OrderLine(
                        UUID.randomUUID(),
                        "SKU-" + UUID.randomUUID(),
                        "Product",
                        Money.usd("1"),
                        1);
        line.bindReservation(UUID.randomUUID(), "WH-1");
        return line;
    }

    private void advanceTo(Order order, OrderStatus target) {
        while (order.getStatus() != target) forward(order);
    }

    private void forward(Order order) {
        switch (order.getStatus()) {
            case DRAFT -> order.place("customer");
            case PLACED -> order.confirm("operator", "Paid");
            case CONFIRMED -> order.requestShipment("warehouse", "Dispatch");
            case FULFILLING -> order.ship("warehouse", "Shipped");
            case SHIPPED -> order.deliver("carrier", "Delivered");
            default -> throw new AssertionError(order.getStatus());
        }
    }
}

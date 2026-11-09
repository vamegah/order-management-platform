package com.example.omp.order.domain;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.shared.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderStateTransitionsTest {
    private Order orderAt(OrderStatus target) {
        var order =
                new Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "customer@example.com",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        var line = new OrderLine(UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1);
        line.setReservationId(UUID.randomUUID());
        order.addLine(line);
        if (target == OrderStatus.CANCELLED) {
            order.cancel("customer", "Changed mind");
            return order;
        }
        if (target.ordinal() >= OrderStatus.PLACED.ordinal()) order.place("customer");
        if (target.ordinal() >= OrderStatus.CONFIRMED.ordinal())
            order.confirm("operator", "Payment checked");
        if (target.ordinal() >= OrderStatus.FULFILLING.ordinal())
            order.requestShipment("warehouse", "Dispatch requested");
        if (target.ordinal() >= OrderStatus.SHIPPED.ordinal())
            order.ship("warehouse", "Dispatched");
        if (target == OrderStatus.DELIVERED) order.deliver("carrier", "Delivered");
        return order;
    }

    @Test
    void completeJourneyRecordsEveryStateAndPlacementTime() {
        var order = orderAt(OrderStatus.DELIVERED);
        assertThat(order.getPlacedAt()).isNotNull();
        assertThat(order.getStatusHistory()).hasSize(6);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getLines()).hasSize(1);
        assertThat(order.getTotal()).isEqualTo(Money.usd("10"));
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"DRAFT", "PLACED", "CONFIRMED"})
    void cancellationIsAllowedBeforeShipping(OrderStatus initial) {
        var order = orderAt(initial);
        int history = order.getStatusHistory().size();
        order.cancel("operator", "Cancelled");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory()).hasSize(history + 1);
    }

    @ParameterizedTest
    @CsvSource({
        "DRAFT,CONFIRM",
        "DRAFT,SHIP",
        "PLACED,SHIP",
        "PLACED,DELIVER",
        "CONFIRMED,DELIVER",
        "CONFIRMED,SHIP",
        "FULFILLING,CANCEL",
        "SHIPPED,CANCEL",
        "DELIVERED,CANCEL",
        "CANCELLED,PLACE",
        "CANCELLED,CONFIRM"
    })
    void invalidTransitionsPreserveStateAndHistory(OrderStatus initial, String action) {
        var order = orderAt(initial);
        int history = order.getStatusHistory().size();
        assertThatThrownBy(
                        () -> {
                            switch (action) {
                                case "PLACE" -> order.place("customer");
                                case "CONFIRM" -> order.confirm("operator", "Confirmed");
                                case "SHIP" -> order.ship("warehouse", "Shipped");
                                case "DELIVER" -> order.deliver("carrier", "Delivered");
                                case "CANCEL" -> order.cancel("operator", "Cancelled");
                                default -> throw new AssertionError("Unknown test action");
                            }
                        })
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(initial);
        assertThat(order.getStatusHistory()).hasSize(history);
    }

    @Test
    void legacyOrderWithoutReservationCannotBeginShipment() {
        var order = orderAt(OrderStatus.CONFIRMED);
        // Rehydrate a historical missing pointer without passing it through new acceptance guards.
        ReflectionTestUtils.setField(order.getLines().getFirst(), "reservationId", null);
        assertThatThrownBy(() -> order.requestShipment("operator", "Requested"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void duplicatedReservationCannotSatisfyTwoShipmentLines() {
        var order = orderAt(OrderStatus.DRAFT);
        var second = new OrderLine(UUID.randomUUID(), "SKU-2", "Product", Money.usd("1"), 1);
        second.setReservationId(UUID.randomUUID());
        order.addLine(second);
        order.place("customer");
        order.confirm("operator", "Paid");
        // Simulate a shared pointer loaded from legacy storage after confirmation.
        second.setReservationId(order.getLines().getFirst().getReservationId());
        assertThatThrownBy(() -> order.requestShipment("operator", "Requested"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}

package com.example.omp.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderNumber;
import com.example.omp.order.domain.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class OrderRepositoryIT extends AbstractIntegrationTest {

    @Autowired private OrderRepository orderRepository;

    @Test
    void findByOrderNumberReturnsSeedOrder() {
        Optional<Order> order =
                orderRepository.findByOrderNumber(OrderNumber.of("ORD-2025-0001001"));

        assertThat(order).isPresent();
        assertThat(order.get().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.get().getCustomerEmail()).isEqualTo("alice@omp.local");
    }

    @Test
    void findByIdWithLinesLoadsLineItems() {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        Optional<Order> order = orderRepository.findByIdWithLines(orderId);

        assertThat(order).isPresent();
        assertThat(order.get().getLines()).hasSize(1);
        assertThat(order.get().getLines().get(0).getProductSku()).isEqualTo("LAPTOP-AIR-13");
    }

    @Test
    void findByIdWithFullDetailsLoadsStatusHistory() {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        Optional<Order> order = orderRepository.findByIdWithFullDetails(orderId);

        assertThat(order).isPresent();
        assertThat(order.get().getStatusHistory())
                .hasSize(5); // DRAFT→PLACED→CONFIRMED→SHIPPED→DELIVERED
        assertThat(order.get().getStatusHistory())
                .extracting(h -> h.getToStatus().name())
                .containsExactly("DRAFT", "PLACED", "CONFIRMED", "SHIPPED", "DELIVERED");
    }

    @Test
    void findByStatusReturnsMatchingOrders() {
        var page =
                orderRepository.findByStatusOrderByCreatedAtDesc(
                        OrderStatus.DRAFT, org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent()).allMatch(o -> o.getStatus() == OrderStatus.DRAFT);
    }

    @Test
    void seedOrderTotalsAreConsistent() {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        Order order = orderRepository.findByIdWithLines(orderId).orElseThrow();

        // Sanity check: total = subtotal + tax + shipping
        var total = order.getTotal().getAmount();
        var parts =
                order.getSubtotal()
                        .getAmount()
                        .add(order.getTax().getAmount())
                        .add(order.getShipping().getAmount());

        assertThat(total).isEqualByComparingTo(parts);
    }
}

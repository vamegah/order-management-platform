package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderNumber;
import com.example.omp.order.domain.ShippingAddress;
import com.example.omp.order.repository.OrderRepository;
import com.example.omp.shared.domain.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTransactionServiceTest {
    @Test
    void recoveryFencePreventsAcceptanceBeforeAnyAggregateOrEventWrite() {
        var repository = mock(OrderRepository.class);
        var generator = mock(OrderNumberGenerator.class);
        var events = mock(com.example.omp.order.events.OrderEventPublisher.class);
        var journal = mock(CheckoutJournal.class);
        UUID orderId = UUID.randomUUID();
        doThrow(new IllegalStateException("Checkout is fenced by recovery"))
                .when(journal)
                .accept(orderId);
        var service = new OrderTransactionService(repository, generator, events, journal);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        orderId,
                                        null,
                                        null,
                                        List.of(
                                                new OrderTransactionService.OrderLineDraft(
                                                        UUID.randomUUID(),
                                                        "SKU",
                                                        "Product",
                                                        Money.usd("1"),
                                                        1)),
                                        UUID.randomUUID(),
                                        List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository, generator, events);
    }

    @Test
    void persistsReservationWithItsOrderLine() {
        var repository = mock(OrderRepository.class);
        var generator = mock(OrderNumberGenerator.class);
        when(generator.next()).thenReturn(OrderNumber.from(2026, 1));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var events = mock(com.example.omp.order.events.OrderEventPublisher.class);
        var service =
                new OrderTransactionService(
                        repository, generator, events, mock(CheckoutJournal.class));
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Order order =
                service.create(
                        orderId,
                        new CustomerDto(
                                UUID.randomUUID(),
                                "customer@example.com",
                                "A",
                                "B",
                                "ACTIVE",
                                List.of(),
                                null),
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        List.of(
                                new OrderTransactionService.OrderLineDraft(
                                        UUID.randomUUID(),
                                        "SKU-1",
                                        "Product",
                                        Money.usd("5.00"),
                                        2)),
                        UUID.randomUUID(),
                        List.of(reservationId));
        var writes = inOrder(repository, events);
        writes.verify(repository).saveAndFlush(order);
        writes.verify(events).publishCreated(order);
        assertThat(order.getId()).isEqualTo(orderId);
        assertThat(order.getLines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.getReservationId()).isEqualTo(reservationId);
                            assertThat(line.getOrder()).isSameAs(order);
                        });
        assertThat(order.getTotal().getAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void rejectsUnreservedLinesBeforeWriting() {
        var repository = mock(OrderRepository.class);
        var generator = mock(OrderNumberGenerator.class);
        var service =
                new OrderTransactionService(
                        repository,
                        generator,
                        mock(com.example.omp.order.events.OrderEventPublisher.class),
                        mock(CheckoutJournal.class));
        assertThatThrownBy(
                        () ->
                                service.create(
                                        UUID.randomUUID(),
                                        null,
                                        null,
                                        List.of(
                                                new OrderTransactionService.OrderLineDraft(
                                                        UUID.randomUUID(),
                                                        "SKU",
                                                        "Product",
                                                        Money.usd("1"),
                                                        1)),
                                        UUID.randomUUID(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, generator);
    }
}

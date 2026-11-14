package com.example.omp.order.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.dto.OrderFilter;
import com.example.omp.order.service.OrderService;
import com.example.omp.shared.web.pagination.PageableResolver;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

class OrderListFiltersTest {
    private final OrderService orders = mock(OrderService.class);
    private final PageableResolver pages = mock(PageableResolver.class);
    private final OrderController controller = new OrderController(orders, pages);

    @Test
    void offsetDateBoundsAreNormalizedAndOmittedBoundsRemainNull() {
        var page = PageRequest.of(0, 10);
        when(pages.resolve(0, 10, null)).thenReturn(page);
        var customer = UUID.randomUUID();
        var after = OffsetDateTime.parse("2026-11-13T12:00:00+01:00");
        var before = after.plusHours(1);
        controller.list(customer, null, null, 0, 10, null, after, before);
        var filter = ArgumentCaptor.forClass(OrderFilter.class);
        verify(orders).list(filter.capture(), eq(page));
        assertThat(filter.getValue().customerId()).isEqualTo(customer);
        assertThat(filter.getValue().placedAfter()).isEqualTo(after.toInstant());
        assertThat(filter.getValue().placedBefore()).isEqualTo(before.toInstant());
        clearInvocations(orders);
        controller.list(customer, null, null, 0, 10, null, null, null);
        verify(orders).list(new OrderFilter(customer, null, null, null, null), page);
    }

    @Test
    void invertedPlacementWindowIsRejectedBeforeReadingOrders() {
        var later = OffsetDateTime.parse("2026-11-13T12:00:00Z");
        assertThatThrownBy(
                        () ->
                                controller.list(
                                        null, null, null, 0, 10, null, later, later.minusHours(1)))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        verifyNoInteractions(orders);
    }
}

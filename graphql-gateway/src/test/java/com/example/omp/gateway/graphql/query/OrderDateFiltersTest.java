package com.example.omp.gateway.graphql.query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.gateway.clients.OrderClient;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderDateFiltersTest {
    private final OrderClient client = mock(OrderClient.class);
    private final OrderQueryResolver resolver = new OrderQueryResolver(client);

    @Test
    void scalarInstantsAndOffsetStringsReachThePlacementFilter() {
        var after = Instant.parse("2026-11-13T10:00:00Z");
        var before = Instant.parse("2026-11-13T11:00:00Z");
        resolver.orders(
                Map.of("placedAfter", after, "placedBefore", "2026-11-13T12:00:00+01:00"), 0, 10);
        verify(client).listOrdersByPlacement(null, null, null, 0, 10, null, after, before);
        verify(client, never()).listOrders(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void oneSidedDateBoundsAreSupported() {
        var before = Instant.parse("2026-11-13T11:00:00Z");
        resolver.orders(Map.of("placedBefore", before), 0, 10);
        verify(client).listOrdersByPlacement(null, null, null, 0, 10, null, null, before);
    }

    @Test
    void invalidDatesAndInvertedIntervalsFailBeforeAnyDownstreamRequest() {
        assertThatThrownBy(() -> resolver.orders(Map.of("placedAfter", "invalid"), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                resolver.orders(
                                        Map.of(
                                                "placedAfter",
                                                "2026-11-13T12:00:00Z",
                                                "placedBefore",
                                                "2026-11-13T11:00:00Z"),
                                        0,
                                        10))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}

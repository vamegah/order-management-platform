package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.*;
import com.example.omp.order.domain.Order;
import com.example.omp.shared.domain.Money;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class OrderLegacyLifecycleResolverTest {
    private OrderLegacyLifecycleStore store;
    private OrderLegacyLifecycleResolver resolver;
    private Order order;
    private OrderLine line;
    private OrderLifecycleStore.Claim claim;

    @BeforeEach
    void setup() {
        store = mock(OrderLegacyLifecycleStore.class);
        resolver = new OrderLegacyLifecycleResolver(store);
        order =
                new Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "owner@example.test",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        line = new OrderLine(UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1);
        ReflectionTestUtils.setField(line, "id", UUID.randomUUID());
        order.addLine(line);
        claim = new OrderLifecycleStore.Claim(order.getId(), UUID.randomUUID());
    }

    private ReservationDto hold(
            UUID id, String sku, String warehouse, int quantity, String status) {
        return new ReservationDto(id, sku, warehouse, quantity, order.getId(), status, 0, 0);
    }

    @Test
    void aUniqueOriginalHoldCanRepairMissingMetadataWithoutInventingAWarehouse() {
        var hold = hold(UUID.randomUUID(), "SKU", "WH-ORIGINAL", 1, "RESERVATION_STATUS_ACTIVE");
        resolver.resolve(claim, order, List.of(hold));
        verify(store).resolve(claim, order, Map.of(line.getId(), hold), false);
        assertThat(line.getReservationId()).isNull();
        assertThat(line.getReservationWarehouseId()).isNull();
    }

    @Test
    void malformedRemoteMetadataCannotBeClassifiedAsIrrecoverableHistoricalInventory() {
        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        claim,
                                        order,
                                        List.of(
                                                hold(
                                                        UUID.randomUUID(),
                                                        "SKU",
                                                        null,
                                                        1,
                                                        "RESERVATION_STATUS_ACTIVE"))))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    @Test
    void missingOrAmbiguousOriginalHoldsSelectAuditedCancellation() {
        resolver.resolve(claim, order, List.of());
        verify(store).resolve(claim, order, Map.of(), true);
        reset(store);
        resolver.resolve(
                claim,
                order,
                List.of(
                        hold(UUID.randomUUID(), "SKU", "A", 1, "RESERVATION_STATUS_ACTIVE"),
                        hold(UUID.randomUUID(), "SKU", "B", 1, "RESERVATION_STATUS_ACTIVE")));
        verify(store).resolve(claim, order, Map.of(), true);
    }

    @Test
    void knownProvenanceCannotBeReplacedByADifferentHold() {
        UUID original = UUID.randomUUID();
        line.bindReservation(original, "WH-ORIGINAL");
        resolver.resolve(
                claim,
                order,
                List.of(hold(original, "SKU", "WH-OTHER", 1, "RESERVATION_STATUS_ACTIVE")));
        verify(store).resolve(claim, order, Map.of(), true);
    }

    @Test
    void aForeignPointerIsRestoredFromUniqueOriginalOwnedInventory() {
        line.setReservationId(UUID.randomUUID());
        var original = hold(UUID.randomUUID(), "SKU", "ORIGINAL", 1, "RESERVATION_STATUS_ACTIVE");
        resolver.resolve(claim, order, List.of(original));
        verify(store).resolve(claim, order, Map.of(line.getId(), original), false);
    }

    @Test
    void aKnownValidPointerWinsOverOtherMatchingHolds() {
        UUID id = UUID.randomUUID();
        line.setReservationId(id);
        var original = hold(id, "SKU", "ORIGINAL", 1, "RESERVATION_STATUS_COMMITTED");
        resolver.resolve(
                claim,
                order,
                List.of(
                        original,
                        hold(UUID.randomUUID(), "SKU", "OTHER", 1, "RESERVATION_STATUS_ACTIVE")));
        verify(store).resolve(claim, order, Map.of(line.getId(), original), false);
    }

    @Test
    void anExpiredOriginalCannotBeReplacedByALaterActiveHold() {
        UUID id = UUID.randomUUID();
        line.setReservationId(id);
        resolver.resolve(
                claim,
                order,
                List.of(
                        hold(id, "SKU", "ORIGINAL", 1, "RESERVATION_STATUS_EXPIRED"),
                        hold(
                                UUID.randomUUID(),
                                "SKU",
                                "ORIGINAL",
                                1,
                                "RESERVATION_STATUS_ACTIVE")));
        verify(store).resolve(claim, order, Map.of(), true);
    }

    @Test
    void committedOriginalInventoryIsPreservedAsEvidenceForPartialFulfillmentRecovery() {
        UUID original = UUID.randomUUID();
        line.setReservationId(original);
        var hold = hold(original, "SKU", "WH-ORIGINAL", 1, "RESERVATION_STATUS_COMMITTED");
        resolver.resolve(claim, order, List.of(hold));
        verify(store).resolve(claim, order, Map.of(line.getId(), hold), false);
    }

    @Test
    void foreignOrDuplicateLookupResultsCannotProduceAnAuditOrCancellation() {
        var original = hold(UUID.randomUUID(), "SKU", "WH-1", 1, "RESERVATION_STATUS_ACTIVE");
        var foreign =
                new ReservationDto(
                        original.id(),
                        "SKU",
                        "WH-1",
                        1,
                        UUID.randomUUID(),
                        original.status(),
                        0,
                        0);
        assertThatThrownBy(() -> resolver.resolve(claim, order, List.of(foreign)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver.resolve(claim, order, List.of(original, original)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    @Test
    void terminalHoldsAndWrongSkuOrQuantityCannotBeUsedAsAnActiveReference() {
        resolver.resolve(
                claim,
                order,
                List.of(
                        hold(UUID.randomUUID(), "OTHER", "WH-1", 1, "RESERVATION_STATUS_ACTIVE"),
                        hold(UUID.randomUUID(), "SKU", "WH-1", 2, "RESERVATION_STATUS_ACTIVE"),
                        hold(UUID.randomUUID(), "SKU", "WH-1", 1, "RESERVATION_STATUS_EXPIRED")));
        verify(store).resolve(claim, order, Map.of(), true);
    }
}

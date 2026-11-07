package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.dto.ReservationReferenceRepairRequest;
import com.example.omp.order.dto.ReservationReferenceRepairRequest.Binding;
import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.dto.ReservationReferenceRepairView.Line;
import com.example.omp.order.error.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderReservationRepairServiceTest {
    private final UUID orderId = UUID.randomUUID(),
            lineId = UUID.randomUUID(),
            reservationId = UUID.randomUUID();
    private final OrderReservationRepairStore store = mock(OrderReservationRepairStore.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderReservationRepairService service =
            new OrderReservationRepairService(store, inventory);

    @BeforeEach
    void prepare() {
        when(store.inspect(orderId)).thenReturn(view("DRAFT", null));
        when(inventory.get(reservationId))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return reservation(
                                    reservationId, orderId, "SKU", 2, "RESERVATION_STATUS_ACTIVE");
                        });
    }

    @Test
    void verifiesExistingResourceBeforePassingTheCompletePlanToTheTransaction() {
        var expected =
                new ReservationReferenceRepairView(
                        orderId,
                        5,
                        "DRAFT",
                        0,
                        1,
                        List.of(new Line(lineId, "SKU", 2, reservationId)));
        when(store.restore(
                        orderId,
                        4,
                        Map.of(lineId, reservationId),
                        "operator",
                        "Verified remote hold"))
                .thenReturn(expected);

        assertThat(service.restore(orderId, request(), "operator")).isEqualTo(expected);

        var calls = inOrder(inventory, store);
        calls.verify(store).inspect(orderId);
        calls.verify(inventory).get(reservationId);
        calls.verify(store)
                .restore(
                        orderId,
                        4,
                        Map.of(lineId, reservationId),
                        "operator",
                        "Verified remote hold");
        verifyNoMoreInteractions(inventory);
    }

    @Test
    void matchingAlreadyBoundReferencesRemainIdempotentAfterStatusAndVersionChanges() {
        when(store.inspect(orderId)).thenReturn(view("CANCELLED", reservationId));
        var oldVersion =
                new ReservationReferenceRepairRequest(
                        0L, "Verified remote hold", request().bindings());

        assertThat(service.restore(orderId, oldVersion, "operator").restored()).isZero();

        verifyNoInteractions(inventory);
        verify(store, never()).restore(any(), anyLong(), anyMap(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "id",
                "order",
                "sku",
                "quantity",
                "released",
                "expired",
                "committed",
                "unknown"
            })
    void rejectedRemoteEvidenceCannotReachTheWriteTransaction(String fault) {
        var remote =
                switch (fault) {
                    case "null" -> null;
                    case "id" ->
                            reservation(
                                    UUID.randomUUID(),
                                    orderId,
                                    "SKU",
                                    2,
                                    "RESERVATION_STATUS_ACTIVE");
                    case "order" ->
                            reservation(
                                    reservationId,
                                    UUID.randomUUID(),
                                    "SKU",
                                    2,
                                    "RESERVATION_STATUS_ACTIVE");
                    case "sku" ->
                            reservation(
                                    reservationId,
                                    orderId,
                                    "FOREIGN",
                                    2,
                                    "RESERVATION_STATUS_ACTIVE");
                    case "quantity" ->
                            reservation(
                                    reservationId, orderId, "SKU", 3, "RESERVATION_STATUS_ACTIVE");
                    default ->
                            reservation(
                                    reservationId,
                                    orderId,
                                    "SKU",
                                    2,
                                    "RESERVATION_STATUS_" + fault.toUpperCase(Locale.ROOT));
                };
        when(inventory.get(reservationId)).thenReturn(remote);

        assertThatThrownBy(() -> service.restore(orderId, request(), "operator"))
                .isInstanceOf(
                        switch (fault) {
                            case "null", "id" -> DownstreamServiceException.class;
                            case "order", "sku", "quantity" -> InvalidOrderDataException.class;
                            default -> InvalidOrderStateException.class;
                        });

        verify(store, never()).restore(any(), anyLong(), anyMap(), any(), any());
        verify(inventory).get(reservationId);
        verifyNoMoreInteractions(inventory);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null-request",
                "null-version",
                "negative-version",
                "blank-reason",
                "long-reason",
                "null-bindings",
                "empty-bindings",
                "oversized",
                "null-binding",
                "null-line",
                "null-reservation",
                "duplicate-line",
                "duplicate-reservation",
                "foreign-line"
            })
    void malformedOrAmbiguousPlanNeverCallsInventoryOrWrites(String fault) {
        List<Binding> bindings = List.of(new Binding(lineId, reservationId));
        Long version = 4L;
        String reason = "Verified remote hold";
        switch (fault) {
            case "null-version" -> version = null;
            case "negative-version" -> version = -1L;
            case "blank-reason" -> reason = " ";
            case "long-reason" -> reason = "x".repeat(501);
            case "null-bindings" -> bindings = null;
            case "empty-bindings" -> bindings = List.of();
            case "oversized" ->
                    bindings =
                            IntStream.range(0, 101)
                                    .mapToObj(
                                            index ->
                                                    new Binding(
                                                            UUID.randomUUID(), UUID.randomUUID()))
                                    .toList();
            case "null-binding" -> bindings = Arrays.asList((Binding) null);
            case "null-line" -> bindings = List.of(new Binding(null, reservationId));
            case "null-reservation" -> bindings = List.of(new Binding(lineId, null));
            case "duplicate-line" ->
                    bindings =
                            List.of(
                                    new Binding(lineId, reservationId),
                                    new Binding(lineId, UUID.randomUUID()));
            case "duplicate-reservation" ->
                    bindings =
                            List.of(
                                    new Binding(lineId, reservationId),
                                    new Binding(UUID.randomUUID(), reservationId));
            case "foreign-line" ->
                    bindings = List.of(new Binding(UUID.randomUUID(), reservationId));
            default -> {}
        }
        var invalid =
                fault.equals("null-request")
                        ? null
                        : new ReservationReferenceRepairRequest(version, reason, bindings);

        assertThatThrownBy(() -> service.restore(orderId, invalid, "operator"))
                .isInstanceOf(InvalidOrderDataException.class);

        verifyNoInteractions(inventory);
        verify(store, never()).restore(any(), anyLong(), anyMap(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FULFILLING", "SHIPPED", "DELIVERED", "CANCELLED"})
    void incompleteHistoricalReferencesCannotBeRepairedAfterTheFulfillmentFence(String status) {
        when(store.inspect(orderId)).thenReturn(view(status, null));

        assertThatThrownBy(() -> service.restore(orderId, request(), "operator"))
                .isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(inventory);
        verify(store, never()).restore(any(), anyLong(), anyMap(), any(), any());
    }

    @Test
    void cannotReplaceAnExistingReferenceOrIgnoreAStaleVersion() {
        when(store.inspect(orderId)).thenReturn(view("DRAFT", UUID.randomUUID()));
        assertThatThrownBy(() -> service.restore(orderId, request(), "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
        when(store.inspect(orderId)).thenReturn(view("DRAFT", null));
        var stale =
                new ReservationReferenceRepairRequest(
                        3L, "Verified remote hold", request().bindings());
        assertThatThrownBy(() -> service.restore(orderId, stale, "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(inventory);
        verify(store, never()).restore(any(), anyLong(), anyMap(), any(), any());
    }

    @Test
    void unauditableOperatorAndAnExistingTransactionAreRejectedBeforeDiscovery() {
        assertThatThrownBy(() -> service.restore(orderId, request(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.restore(orderId, request(), " "))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.restore(orderId, request(), "x".repeat(201)))
                .isInstanceOf(AccessDeniedException.class);
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> service.restore(orderId, request(), "operator"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(inventory, store);
    }

    @Test
    void lookupErrorsDoNotExposeRemoteDescriptionsOrWriteReferences() {
        when(inventory.get(reservationId))
                .thenThrow(new IllegalArgumentException("secret remote metadata"));
        assertThatThrownBy(() -> service.restore(orderId, request(), "operator"))
                .isInstanceOfSatisfying(
                        DownstreamServiceException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain("secret"));
        doThrow(new ProductUnavailableException("secret remote metadata"))
                .when(inventory)
                .get(reservationId);
        assertThatThrownBy(() -> service.restore(orderId, request(), "operator"))
                .isInstanceOfSatisfying(
                        InvalidOrderDataException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain("secret"));
        verify(store, never()).restore(any(), anyLong(), anyMap(), any(), any());
    }

    private ReservationReferenceRepairRequest request() {
        return new ReservationReferenceRepairRequest(
                4L, "  Verified remote hold  ", List.of(new Binding(lineId, reservationId)));
    }

    private ReservationReferenceRepairView view(String status, UUID reference) {
        return new ReservationReferenceRepairView(
                orderId,
                4,
                status,
                reference == null ? 1 : 0,
                0,
                List.of(new Line(lineId, "SKU", 2, reference)));
    }

    private static ReservationDto reservation(
            UUID id, UUID owner, String sku, int quantity, String status) {
        return new ReservationDto(id, sku, "WH-1", quantity, owner, status, 0, 0);
    }
}

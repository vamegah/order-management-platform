package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.*;
import com.example.omp.proto.inventory.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationCompensationTest {
    private ReservationRepository reservations;
    private StockLevelRepository stocks;
    private InventoryEventPublisher events;
    private ReservationHandler handler;
    private UUID orderId;

    @BeforeEach
    void setup() {
        reservations = mock(ReservationRepository.class);
        stocks = mock(StockLevelRepository.class);
        events = mock(InventoryEventPublisher.class);
        handler = new ReservationHandler(reservations, stocks, events);
        orderId = UUID.randomUUID();
        when(reservations.tryLockOrder(orderId)).thenReturn(true);
    }

    private Reservation hold(String sku, int quantity) {
        var reservation = new Reservation(sku, "WH-1", quantity, orderId);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(reservation, "createdAt", Instant.now());
        ReflectionTestUtils.setField(reservation, "updatedAt", Instant.now());
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        return reservation;
    }

    @Test
    void partialCommitCompensationConservesStockAndRepeatedCallsDoNotRestockAgain() {
        var committed = hold("A", 3);
        var active = hold("B", 2);
        var stockA = new StockLevel("A", "WH-1", 10);
        var stockB = new StockLevel("B", "WH-1", 10);
        stockA.reserve(3);
        stockA.commit(3);
        committed.commit();
        stockB.reserve(2);
        when(stocks.findBySkuAndWarehouseId("A", "WH-1")).thenReturn(Optional.of(stockA));
        when(stocks.findBySkuAndWarehouseId("B", "WH-1")).thenReturn(Optional.of(stockB));
        when(reservations.findTop101ByOrderIdOrderByCreatedAtAsc(orderId))
                .thenReturn(List.of(committed, active));
        var request = CompensateOrderRequest.newBuilder().setOrderId(orderId.toString()).build();
        var response = handler.compensateOrder(request);
        assertThat(response.getReservationsList())
                .extracting(com.example.omp.proto.inventory.Reservation::getStatus)
                .containsExactly(
                        ReservationStatus.RESERVATION_STATUS_RESTOCKED,
                        ReservationStatus.RESERVATION_STATUS_RELEASED);
        handler.compensateOrder(request);
        assertThat(stockA.getQuantityOnHand()).isEqualTo(10);
        assertThat(stockB.getQuantityOnHand()).isEqualTo(10);
        assertThat(stockA.getQuantityReserved()).isZero();
        assertThat(stockB.getQuantityReserved()).isZero();
        verify(events, times(2)).publishReservationReleased(any(), eq("Order compensation"));
    }

    @Test
    void cancellationTombstonesRejectDelayedReserveAndCommit() {
        when(reservations.isOrderCompensated(orderId)).thenReturn(true);
        var original = hold("A", 3);
        assertThatThrownBy(
                        () ->
                                handler.reserve(
                                        ReserveStockRequest.newBuilder()
                                                .setOrderId(orderId.toString())
                                                .setSku("A")
                                                .setWarehouseId("WH-1")
                                                .setQuantity(3)
                                                .build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                handler.commit(
                                        CommitReservationRequest.newBuilder()
                                                .setReservationId(original.getId().toString())
                                                .build()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(stocks, events);
    }

    @Test
    void anExpiredHoldCannotBeCommittedBeforeTheExpirySchedulerGetsToIt() {
        var original = hold("A", 3);
        var stock = new StockLevel("A", "WH-1", 10);
        stock.reserve(3);
        when(stocks.findBySkuAndWarehouseId("A", "WH-1")).thenReturn(Optional.of(stock));
        when(reservations.isExpired(eq(original.getId()), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(handler, "expiryEnabled", true);
        var result =
                handler.commit(
                        CommitReservationRequest.newBuilder()
                                .setReservationId(original.getId().toString())
                                .build());
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_EXPIRED);
        assertThat(stock.getQuantityOnHand()).isEqualTo(10);
        assertThat(stock.getQuantityReserved()).isZero();
        verify(events).publishReservationExpired(original, "Reservation TTL exceeded");
        verify(events, never()).publishReservationCommitted(any(), any());
    }

    @Test
    void aBusyOrderCannotBeMutatedByAnotherExecutor() {
        when(reservations.tryLockOrder(orderId)).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                handler.compensateOrder(
                                        CompensateOrderRequest.newBuilder()
                                                .setOrderId(orderId.toString())
                                                .build()))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(reservations, never()).markOrderCompensated(any());
        verifyNoInteractions(stocks, events);
    }

    @Test
    void oversizedCompensationCannotPartlyRestoreItsFirstHundredResources() {
        var resources = IntStream.range(0, 101).mapToObj(i -> hold("SKU-" + i, 1)).toList();
        when(reservations.findTop101ByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(resources);
        assertThatThrownBy(
                        () ->
                                handler.compensateOrder(
                                        CompensateOrderRequest.newBuilder()
                                                .setOrderId(orderId.toString())
                                                .build()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(stocks, events);
    }

    @Test
    void repeatedReleaseOfRestockedInventoryCannotIncreaseStock() {
        var original = hold("A", 1);
        original.commit();
        original.compensateCommitted();
        original.compensateCommitted();
        var result =
                handler.release(
                        ReleaseReservationRequest.newBuilder()
                                .setReservationId(original.getId().toString())
                                .build());
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RESTOCKED);
        verifyNoInteractions(stocks, events);
    }
}

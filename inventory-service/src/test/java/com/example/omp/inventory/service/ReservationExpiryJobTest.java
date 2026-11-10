package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.domain.*;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationExpiryJobTest {
    @Test
    void expiresHoldAndRecordsEventInTransaction() {
        var reservations = mock(ReservationRepository.class);
        when(reservations.tryLockOrder(any())).thenReturn(true);
        var stocks = mock(StockLevelRepository.class);
        var events = mock(InventoryEventPublisher.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var reservation = new Reservation("SKU", "WH-1", 3, UUID.randomUUID());
        var stock = new StockLevel("SKU", "WH-1", 10);
        stock.reserve(3);
        when(reservations.lockExpiredByTtl(anyLong(), eq(10))).thenReturn(List.of(reservation));
        when(stocks.findBySkuAndWarehouseId("SKU", "WH-1")).thenReturn(Optional.of(stock));
        var metrics = new SimpleMeterRegistry();
        new ReservationExpiryJob(
                        reservations,
                        stocks,
                        events,
                        new TransactionTemplate(manager),
                        metrics,
                        Duration.ofMinutes(30),
                        10)
                .run();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(stock.getQuantityReserved()).isZero();
        var ordered = inOrder(events, manager);
        ordered.verify(events).publishReservationExpired(eq(reservation), anyString());
        ordered.verify(manager).commit(any());
        assertThat(metrics.get("inventory.reservation.expiry.completed").counter().count())
                .isEqualTo(1);
    }

    @Test
    void missingStockRollsBackAndIsRetriedOnNextTick() {
        var reservations = mock(ReservationRepository.class);
        when(reservations.tryLockOrder(any())).thenReturn(true);
        var stocks = mock(StockLevelRepository.class);
        var events = mock(InventoryEventPublisher.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(reservations.lockExpiredByTtl(anyLong(), anyInt()))
                .thenReturn(List.of(new Reservation("SKU", "WH-1", 1, UUID.randomUUID())));
        var metrics = new SimpleMeterRegistry();
        var job =
                new ReservationExpiryJob(
                        reservations,
                        stocks,
                        events,
                        new TransactionTemplate(manager),
                        metrics,
                        Duration.ofMinutes(30),
                        10);
        job.run();
        job.run();
        verify(manager, times(2)).rollback(any());
        verify(manager, never()).commit(any());
        verifyNoInteractions(events);
        assertThat(metrics.get("inventory.reservation.expiry.failures").counter().count())
                .isEqualTo(2);
    }
}

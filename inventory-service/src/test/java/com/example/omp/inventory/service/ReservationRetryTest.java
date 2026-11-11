package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.*;
import com.example.omp.proto.inventory.ReserveStockRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationRetryTest {
    @Test
    void changedRetryCannotSilentlyReuseInsufficientHold() {
        UUID orderId = UUID.randomUUID();
        var repository = mock(ReservationRepository.class);
        when(repository.tryLockOrder(any())).thenReturn(true);
        var stocks = mock(StockLevelRepository.class);
        var events = mock(InventoryEventPublisher.class);
        when(repository.findByOrderIdAndSku(orderId, "SKU"))
                .thenReturn(Optional.of(new Reservation("SKU", "WH-1", 1, orderId)));
        var handler = new ReservationHandler(repository, stocks, events);
        var request =
                ReserveStockRequest.newBuilder()
                        .setOrderId(orderId.toString())
                        .setSku("SKU")
                        .setWarehouseId("WH-1")
                        .setQuantity(2)
                        .build();
        assertThatThrownBy(() -> handler.reserve(request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stocks, events);
    }

    @Test
    void expiredHoldCannotBeReactivatedByRetry() {
        UUID orderId = UUID.randomUUID();
        var repository = mock(ReservationRepository.class);
        when(repository.tryLockOrder(any())).thenReturn(true);
        var reservation = new Reservation("SKU", "WH-1", 1, orderId);
        reservation.expire();
        when(repository.findByOrderIdAndSku(orderId, "SKU")).thenReturn(Optional.of(reservation));
        var handler =
                new ReservationHandler(
                        repository,
                        mock(StockLevelRepository.class),
                        mock(InventoryEventPublisher.class));
        var request =
                ReserveStockRequest.newBuilder()
                        .setOrderId(orderId.toString())
                        .setSku("SKU")
                        .setWarehouseId("WH-1")
                        .setQuantity(1)
                        .build();
        assertThatThrownBy(() -> handler.reserve(request))
                .isInstanceOf(IllegalStateException.class);
    }
}

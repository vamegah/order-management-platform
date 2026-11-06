package com.example.omp.inventory.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.config.InventoryRetryConfig;
import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.error.InsufficientStockException;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.*;
import com.example.omp.proto.inventory.ReserveStockRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;

class ReservationRetryActivationTest {
    @TestConfiguration
    @Import(InventoryRetryConfig.class)
    static class Beans {
        @Bean
        ReservationRepository reservations() {
            return mock(ReservationRepository.class);
        }

        @Bean
        StockLevelRepository stocks() {
            return mock(StockLevelRepository.class);
        }

        @Bean
        ReservationHandler handler(
                ReservationRepository reservations, StockLevelRepository stocks) {
            return new ReservationHandler(
                    reservations, stocks, mock(InventoryEventPublisher.class));
        }
    }

    private ReserveStockRequest request() {
        return ReserveStockRequest.newBuilder()
                .setOrderId(UUID.randomUUID().toString())
                .setSku("SKU")
                .setWarehouseId("WH-1")
                .setQuantity(1)
                .build();
    }

    @Test
    void optimisticContentionIsActuallyRetriedThreeTimes() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            var stocks = context.getBean(StockLevelRepository.class);
            when(stocks.findBySkuAndWarehouseId(anyString(), anyString()))
                    .thenThrow(new OptimisticLockingFailureException("Conflict"));
            assertThatThrownBy(() -> context.getBean(ReservationHandler.class).reserve(request()))
                    .isInstanceOf(OptimisticLockingFailureException.class);
            verify(stocks, times(3)).findBySkuAndWarehouseId("SKU", "WH-1");
        }
    }

    @Test
    void insufficientStockDoesNotRetryABusinessRejection() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            var stocks = context.getBean(StockLevelRepository.class);
            when(stocks.findBySkuAndWarehouseId("SKU", "WH-1"))
                    .thenReturn(Optional.of(new StockLevel("SKU", "WH-1", 0)));
            assertThatThrownBy(() -> context.getBean(ReservationHandler.class).reserve(request()))
                    .isInstanceOf(InsufficientStockException.class);
            verify(stocks).findBySkuAndWarehouseId("SKU", "WH-1");
        }
    }
}

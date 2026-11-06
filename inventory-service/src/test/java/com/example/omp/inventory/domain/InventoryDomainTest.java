package com.example.omp.inventory.domain;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.inventory.error.InsufficientStockException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class InventoryDomainTest {
    @Test
    void releaseCommitAndRestockPreservePhysicalAndAvailableCounts() {
        var stock = new StockLevel("SKU", "WH", 10);
        stock.reserve(4);
        stock.reserve(1);
        assertThat(stock.getQuantityAvailable()).isEqualTo(5);
        stock.release(2);
        assertThat(stock.getQuantityOnHand()).isEqualTo(10);
        assertThat(stock.getQuantityReserved()).isEqualTo(3);
        stock.commit(3);
        assertThat(stock.getQuantityOnHand()).isEqualTo(7);
        assertThat(stock.getQuantityReserved()).isZero();
        stock.restock(5);
        assertThat(stock.getQuantityAvailable()).isEqualTo(12);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void nonpositiveOperationsPreserveStock(int quantity) {
        var stock = new StockLevel("SKU", "WH", 10);
        stock.reserve(3);
        assertThatThrownBy(() -> stock.reserve(quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.release(quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.commit(quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.restock(quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stock.getQuantityOnHand()).isEqualTo(10);
        assertThat(stock.getQuantityReserved()).isEqualTo(3);
    }

    @Test
    void overdraftsCannotMutatePhysicalOrReservedStock() {
        assertThatThrownBy(() -> new StockLevel("SKU", "WH", -1))
                .isInstanceOf(IllegalArgumentException.class);
        var stock = new StockLevel("SKU", "WH", 5);
        stock.reserve(3);
        assertThatThrownBy(() -> stock.reserve(3)).isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> stock.release(4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stock.commit(4)).isInstanceOf(IllegalStateException.class);
        assertThat(stock.getQuantityOnHand()).isEqualTo(5);
        assertThat(stock.getQuantityReserved()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"COMMITTED", "RELEASED", "EXPIRED"})
    void terminalHoldsCannotTransitionAgain(ReservationStatus terminal) {
        var hold = new Reservation("SKU", "WH", 2, UUID.randomUUID());
        switch (terminal) {
            case COMMITTED -> hold.commit();
            case RELEASED -> hold.release();
            case EXPIRED -> hold.expire();
            default -> throw new AssertionError();
        }
        assertThat(hold.getStatus()).isEqualTo(terminal);
        assertThat(hold.getStatus().isTerminal()).isTrue();
        assertThatThrownBy(hold::commit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(hold::release).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(hold::expire).isInstanceOf(IllegalStateException.class);
        assertThat(hold.getStatus()).isEqualTo(terminal);
    }

    @Test
    void aNewHoldMustHavePositiveQuantity() {
        assertThatThrownBy(() -> new Reservation("SKU", "WH", 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

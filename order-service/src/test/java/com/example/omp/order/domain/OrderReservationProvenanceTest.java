package com.example.omp.order.domain;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.shared.domain.Money;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OrderReservationProvenanceTest {
    private final OrderLine line =
            new OrderLine(UUID.randomUUID(), "SKU", "Product", Money.usd("1"), 1);

    @Test
    void originalBindingIsIdempotentAndNeitherComponentCanBeReplaced() {
        UUID id = UUID.randomUUID();
        line.bindReservation(id, "WH-9");
        line.bindReservation(id, "WH-9");
        line.setReservationId(id);
        assertThatThrownBy(() -> line.bindReservation(UUID.randomUUID(), "WH-9"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> line.bindReservation(id, "WH-2"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> line.setReservationId(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(line.getReservationId()).isEqualTo(id);
        assertThat(line.getReservationWarehouseId()).isEqualTo("WH-9");
    }

    @ParameterizedTest
    @MethodSource("invalidWarehouses")
    void invalidWarehouseCannotLeaveAPartialBinding(String warehouse) {
        assertThatThrownBy(() -> line.bindReservation(UUID.randomUUID(), warehouse))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(line.getReservationId()).isNull();
        assertThat(line.getReservationWarehouseId()).isNull();
    }

    private static Stream<String> invalidWarehouses() {
        return Stream.of(null, "", " ", "\t", "\n", " WH-1", "WH-1 ", "x".repeat(65));
    }

    @Test
    void aHistoricalReferenceDoesNotInventAnOriginalWarehouse() {
        UUID id = UUID.randomUUID();
        line.setReservationId(id);
        assertThat(line.getReservationId()).isEqualTo(id);
        assertThat(line.getReservationWarehouseId()).isNull();
    }

    @Test
    void missingIdentityCannotLeaveWarehouseProvenance() {
        assertThatThrownBy(() -> line.bindReservation(null, "WH-1"))
                .isInstanceOf(NullPointerException.class);
        assertThat(line.getReservationId()).isNull();
        assertThat(line.getReservationWarehouseId()).isNull();
    }
}

package com.example.omp.order.web;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.dto.TerminalReservationDispositionView;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

class TerminalReservationDispositionSchemaTest {
    @Test
    void dispositionModelsHaveNamesThatPreserveTheExistingRepairLineContract() {
        var schemas =
                ModelConverters.getInstance().readAll(TerminalReservationDispositionView.class);
        assertThat(schemas)
                .containsKeys(
                        "TerminalReservationDispositionView",
                        "TerminalReservationDispositionLine",
                        "TerminalReservationDispositionAudit")
                .doesNotContainKeys("Line", "Audit");
        Schema<?> lines =
                (Schema<?>)
                        schemas.get("TerminalReservationDispositionView")
                                .getProperties()
                                .get("lines");
        assertThat(lines.getItems().get$ref())
                .isEqualTo("#/components/schemas/TerminalReservationDispositionLine");
        Schema<?> audit =
                (Schema<?>)
                        schemas.get("TerminalReservationDispositionLine")
                                .getProperties()
                                .get("audit");
        assertThat(audit.get$ref())
                .isEqualTo("#/components/schemas/TerminalReservationDispositionAudit");
        var repair = ModelConverters.getInstance().readAll(ReservationReferenceRepairView.class);
        assertThat(repair.get("Line").getProperties())
                .containsOnlyKeys(
                        "lineId", "sku", "quantity", "reservationId", "reservationWarehouseId");
        Schema<?> warehouse =
                (Schema<?>) repair.get("Line").getProperties().get("reservationWarehouseId");
        assertThat(warehouse.getType()).isEqualTo("string");
        assertThat(warehouse.getMaxLength()).isEqualTo(64);
        assertThat(warehouse.getNullable()).isTrue();
        assertThat(warehouse.getReadOnly()).isTrue();
    }
}

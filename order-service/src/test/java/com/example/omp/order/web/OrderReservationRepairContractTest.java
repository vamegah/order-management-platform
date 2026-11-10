package com.example.omp.order.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.order.dto.ReservationReferenceCensusPage;
import com.example.omp.order.dto.ReservationReferenceRepairRequest;
import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.dto.ReservationReferenceRepairView.Line;
import com.example.omp.order.service.OrderReservationRepairService;
import com.example.omp.order.service.ReservationReferenceCensus;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderReservationRepairContractTest {
    private final UUID orderId = UUID.randomUUID(),
            lineId = UUID.randomUUID(),
            reservationId = UUID.randomUUID();
    private final OrderReservationRepairService service = mock(OrderReservationRepairService.class);
    private final ReservationReferenceCensus census = mock(ReservationReferenceCensus.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OrderReservationRepairController(service, census))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ProblemDetailsFactory(mock(Tracer.class))))
                        .build();
    }

    @Test
    void censusReturnsAnAdvancingCursorEvenWhenThePageHasNoFindings() throws Exception {
        when(census.scan(lineId, 2))
                .thenReturn(new ReservationReferenceCensusPage(2, reservationId, List.of()));
        mvc.perform(
                        get("/api/v1/order-reservation-repairs/census")
                                .param("afterLineId", lineId.toString())
                                .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(2))
                .andExpect(jsonPath("$.nextAfterLineId").value(reservationId.toString()))
                .andExpect(jsonPath("$.findings").isEmpty());
        verify(census).scan(lineId, 2);
        verifyNoInteractions(service);
    }

    @Test
    void malformedCensusCursorFailsBeforeServiceCalls() throws Exception {
        mvc.perform(
                        get("/api/v1/order-reservation-repairs/census")
                                .param("afterLineId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request parameter"));
        verifyNoInteractions(census, service);
    }

    @Test
    void inspectionReturnsTheVersionAndMissingReferencesForOperatorPlanning() throws Exception {
        when(service.inspect(orderId))
                .thenReturn(
                        new ReservationReferenceRepairView(
                                orderId,
                                4,
                                "DRAFT",
                                1,
                                0,
                                List.of(new Line(lineId, "SKU", 2, null, "WH-9"))));
        mvc.perform(get("/api/v1/order-reservation-repairs/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.missing").value(1))
                .andExpect(jsonPath("$.lines[0].lineId").value(lineId.toString()))
                .andExpect(jsonPath("$.lines[0].reservationWarehouseId").value("WH-9"));
    }

    @Test
    void repairPassesTheAuthenticatedOperatorAndCompleteValidatedBody() throws Exception {
        when(service.restore(eq(orderId), any(), eq("operator-subject")))
                .thenReturn(
                        new ReservationReferenceRepairView(
                                orderId,
                                5,
                                "DRAFT",
                                0,
                                1,
                                List.of(new Line(lineId, "SKU", 2, reservationId, null))));
        mvc.perform(
                        post("/api/v1/order-reservation-repairs/{id}", orderId)
                                .principal(
                                        new UsernamePasswordAuthenticationToken(
                                                "operator-subject", "unused"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("4", "Verified hold", lineId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1))
                .andExpect(jsonPath("$.version").value(5));
        verify(service)
                .restore(
                        eq(orderId),
                        eq(
                                new ReservationReferenceRepairRequest(
                                        4L,
                                        "Verified hold",
                                        List.of(
                                                new ReservationReferenceRepairRequest.Binding(
                                                        lineId, reservationId)))),
                        eq("operator-subject"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "reason", "line"})
    void incompleteRepairDocumentsFailValidationBeforeServiceCalls(String missing)
            throws Exception {
        String content =
                body(
                        missing.equals("version") ? "null" : "4",
                        missing.equals("reason") ? " " : "Verified hold",
                        lineId.toString());
        if (missing.equals("line"))
            content = content.replace('"' + lineId.toString() + '"', "null");
        mvc.perform(
                        post("/api/v1/order-reservation-repairs/{id}", orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    private String body(String version, String reason, String line) {
        return """
                {"expectedVersion":%s,"reason":"%s","bindings":[{"lineId":"%s","reservationId":"%s"}]}
                """
                .formatted(version, reason, line, reservationId);
    }
}

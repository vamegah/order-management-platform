package com.example.omp.order.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.order.dto.RecoveryAdoptionRequest;
import com.example.omp.order.dto.RecoveryAdoptionResult;
import com.example.omp.order.service.CheckoutRecoveryAdoption;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckoutRecoveryAdoptionContractTest {
    private final UUID order = UUID.randomUUID();
    private final CheckoutRecoveryAdoption service = mock(CheckoutRecoveryAdoption.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new CheckoutRecoveryAdoptionController(service))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ProblemDetailsFactory(mock(Tracer.class))))
                        .build();
    }

    @Test
    void submissionUsesTheAuthenticatedSubjectAndReportsAdmissionRatherThanCleanupCompletion()
            throws Exception {
        when(service.adopt(eq(order), any(), eq("operator-subject")))
                .thenReturn(
                        new RecoveryAdoptionResult(
                                order,
                                "FAILED",
                                true,
                                "operator-subject",
                                "Reviewed orphan",
                                Instant.parse("2026-11-07T00:00:00Z")));
        mvc.perform(
                        post("/api/v1/order-recovery/{id}/adopt", order)
                                .principal(
                                        new UsernamePasswordAuthenticationToken(
                                                "operator-subject", "unused"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Reviewed orphan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.requestedBy").value("operator-subject"));
        verify(service)
                .adopt(order, new RecoveryAdoptionRequest("Reviewed orphan"), "operator-subject");
    }

    @Test
    void invalidTypedOrderIdentityReturnsAStableBadRequest() throws Exception {
        mvc.perform(
                        post("/api/v1/order-recovery/{id}/adopt", "not-a-uuid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Reviewed orphan\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid request parameter"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "blank", "oversized"})
    void anInvalidReasonCannotReachTheAdmissionService(String fault) throws Exception {
        String body =
                switch (fault) {
                    case "missing" -> "{}";
                    case "null" -> "{\"reason\":null}";
                    case "blank" -> "{\"reason\":\" \"}";
                    default -> "{\"reason\":\"" + "x".repeat(501) + "\"}";
                };
        mvc.perform(
                        post("/api/v1/order-recovery/{id}/adopt", order)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }
}

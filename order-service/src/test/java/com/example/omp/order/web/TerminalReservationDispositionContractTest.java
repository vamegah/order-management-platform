package com.example.omp.order.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.order.dto.TerminalReservationDispositionRequest;
import com.example.omp.order.dto.TerminalReservationDispositionView;
import com.example.omp.order.service.TerminalReservationDispositionService;
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

class TerminalReservationDispositionContractTest {
    private final UUID order = UUID.randomUUID(), line = UUID.randomUUID();
    private final TerminalReservationDispositionService service =
            mock(TerminalReservationDispositionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new TerminalReservationDispositionController(service))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ProblemDetailsFactory(mock(Tracer.class))))
                        .build();
    }

    @Test
    void recordingUsesTheAuthenticatedSubjectAndReturnsTheRecordedVersion() throws Exception {
        when(service.record(eq(order), any(), eq("verified-admin")))
                .thenReturn(
                        new TerminalReservationDispositionView(
                                order, 3, "DELIVERED", 1, List.of()));
        mvc.perform(
                        post(path())
                                .principal(
                                        new UsernamePasswordAuthenticationToken(
                                                "verified-admin", "unused"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("Reason", "Evidence", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(1))
                .andExpect(jsonPath("$.version").value(3));
        verify(service)
                .record(
                        order,
                        new TerminalReservationDispositionRequest(
                                2L, "Reason", "Evidence", List.of(line)),
                        "verified-admin");
    }

    @Test
    void inspectionReturnsThePlanningResource() throws Exception {
        when(service.inspect(order))
                .thenReturn(
                        new TerminalReservationDispositionView(
                                order, 2, "CANCELLED", 0, List.of()));
        mvc.perform(get(path()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.recorded").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reason", "evidence", "version", "line"})
    void incompleteAttestationsFailBeforeTheService(String absent) throws Exception {
        var content =
                body(
                        absent.equals("reason") ? " " : "Reason",
                        absent.equals("evidence") ? " " : "Evidence",
                        absent.equals("version") ? "null" : "2");
        if (absent.equals("line")) content = content.replace('"' + line.toString() + '"', "null");
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    private String path() {
        return "/api/v1/order-reservation-repairs/" + order + "/terminal-dispositions";
    }

    private String body(String reason, String evidence, String version) {
        return """
                {"expectedVersion":%s,"reason":"%s","evidence":"%s","lineIds":["%s"]}
                """
                .formatted(version, reason, evidence, line);
    }
}

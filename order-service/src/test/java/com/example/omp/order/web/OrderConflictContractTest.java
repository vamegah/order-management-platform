package com.example.omp.order.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.order.service.OrderService;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import com.example.omp.shared.web.pagination.PageableResolver;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderConflictContractTest {
    @Test
    void deniedAccessUsesTheSameHttpAndProblemStatusWithoutUpstreamDetails() throws Exception {
        UUID id = UUID.randomUUID();
        var service = mock(OrderService.class);
        when(service.getById(eq(id), anyBoolean()))
                .thenThrow(
                        new com.example.omp.order.error.OrderAccessDeniedException(
                                "secret downstream customer"));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new OrderController(service, mock(PageableResolver.class)))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ProblemDetailsFactory(mock(Tracer.class))))
                        .build();
        mvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("Access to this order was denied."));
    }

    @Test
    void concurrentUpdatesReturnARedactedConflictProblem() throws Exception {
        UUID id = UUID.randomUUID();
        var service = mock(OrderService.class);
        when(service.getById(eq(id), anyBoolean()))
                .thenThrow(
                        new ObjectOptimisticLockingFailureException("secret.database.Entity", id));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new OrderController(service, mock(PageableResolver.class)))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ProblemDetailsFactory(mock(Tracer.class))))
                        .build();
        mvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Order changed concurrently. Reload and retry."));
    }
}

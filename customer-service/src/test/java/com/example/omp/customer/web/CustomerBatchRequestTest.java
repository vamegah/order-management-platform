package com.example.omp.customer.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.omp.customer.service.CustomerService;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import com.example.omp.shared.web.pagination.PageableResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerBatchRequestTest {
    @Test
    void missingOrMalformedIdsReturnBadRequestWithoutQueryingCustomers() throws Exception {
        var service = mock(CustomerService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CustomerController(service, mock(PageableResolver.class)))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(mock(ProblemDetailsFactory.class)))
                        .build();
        mvc.perform(get("/api/v1/customers/batch")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers/batch").param("ids", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

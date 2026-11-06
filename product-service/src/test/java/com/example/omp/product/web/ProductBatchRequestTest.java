package com.example.omp.product.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.omp.product.service.ProductService;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import com.example.omp.shared.web.pagination.PageableResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductBatchRequestTest {
    @Test
    void missingOrMalformedIdsReturnBadRequestWithoutQueryingProducts() throws Exception {
        var service = mock(ProductService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ProductController(service, mock(PageableResolver.class)))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(mock(ProblemDetailsFactory.class)))
                        .build();
        mvc.perform(get("/api/v1/products/batch")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/batch").param("ids", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

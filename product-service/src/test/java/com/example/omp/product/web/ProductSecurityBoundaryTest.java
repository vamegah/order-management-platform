package com.example.omp.product.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.product.TestSecurityConfig;
import com.example.omp.product.config.SecurityConfig;
import com.example.omp.product.service.ProductService;
import com.example.omp.shared.web.config.SharedWebAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, SharedWebAutoConfiguration.class, TestSecurityConfig.class})
@ActiveProfiles("test")
class ProductSecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @MockBean ProductService products;

    @Test
    void anonymousBatchDoesNotReadProducts() throws Exception {
        mvc.perform(
                        get("/api/v1/products/batch")
                                .param("ids", "00000000-0000-0000-0000-000000000201"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(products);
    }

    @Test
    void invalidBearerIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer unknown-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(products);
    }

    @Test
    void authenticatedCustomerCanReadBatch() throws Exception {
        when(products.getBatch(anyList())).thenReturn(List.of());
        mvc.perform(
                        get("/api/v1/products/batch")
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.aliceToken())
                                .param("ids", "00000000-0000-0000-0000-000000000201"))
                .andExpect(status().isOk());
        verify(products).getBatch(anyList());
    }

    @Test
    void configuredPaginationAcceptsNameSort() throws Exception {
        mvc.perform(
                        get("/api/v1/products")
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.aliceToken())
                                .param("sort", "name,asc"))
                .andExpect(status().isOk());
        verify(products)
                .list(any(), argThat(page -> page.getSort().getOrderFor("name").isAscending()));
    }

    @Test
    void allowedOriginCanPreflightWithoutAuthentication() throws Exception {
        mvc.perform(
                        options("/api/v1/products")
                                .header("Origin", "http://localhost:3000")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        verifyNoInteractions(products);
    }

    @Test
    void unknownOriginCannotPreflight() throws Exception {
        mvc.perform(
                        options("/api/v1/products")
                                .header("Origin", "https://untrusted.example")
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(products);
    }
}

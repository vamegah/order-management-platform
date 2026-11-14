package com.example.omp.customer.config;

import com.example.omp.shared.web.openapi.PublishedApiConfiguration;
import com.example.omp.shared.web.openapi.PublishedApiSupport;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(PublishedApiConfiguration.class)
public class OpenApiConfig {
    @Bean
    public OpenAPI customerServiceOpenApi() {
        var document =
                PublishedApiSupport.definition(
                        "Customer Service API",
                        "customer-service.localhost",
                        "Verified customer ownership or administrator access for private profiles and addresses. Listing and writes require an administrator.");

        return document;
    }
}

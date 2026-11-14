package com.example.omp.order.config;

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
    public OpenAPI orderServiceOpenApi() {
        var document =
                PublishedApiSupport.definition(
                        "Order Service API",
                        "order-service.localhost",
                        "Verified owner or administrator order access, durable checkout and lifecycle recovery. Pending work returns HTTP 202.");

        return document;
    }
}

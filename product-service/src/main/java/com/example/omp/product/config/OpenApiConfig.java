package com.example.omp.product.config;

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
    public OpenAPI productServiceOpenApi() {
        var document =
                PublishedApiSupport.definition(
                        "Product Service API",
                        "product-service.localhost",
                        "Authenticated catalog reads and administrator writes. Product lines remain queryable after soft deletion.");

        return document;
    }
}

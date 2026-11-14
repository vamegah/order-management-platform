package com.example.omp.adapter.rest;

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
    public OpenAPI publishedOpenApi() {
        var document =
                PublishedApiSupport.definition(
                        "Banking Facade API",
                        "banking.localhost",
                        "Administrator banking facade over the legacy SOAP authority. Idempotency-Key preserves exact transfer retries and rejects conflicting payloads.");

        return document;
    }
}

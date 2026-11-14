package com.example.omp.notification.config;

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
                        "Webhook Management API",
                        "notification.localhost",
                        "Owner-scoped webhook subscriptions and delivery history. DLQ inspection and replay require an administrator. Secrets are returned only at creation or rotation.");

        return document;
    }
}

package com.example.omp.payment.config;

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
                        "Payment HTTP API",
                        "payment-service.localhost",
                        "Owner-scoped payment and refund operations, administrator recovery and signed provider callbacks. Caller idempotency keys are reused after uncertain outcomes.");

        document.getComponents()
                .addSecuritySchemes(
                        "providerSignature",
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY)
                                .in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER)
                                .name("Stripe-Signature")
                                .description(
                                        "Provider HMAC signature over the unmodified request bytes; this callback does not accept a JWT as its authentication."));

        document.getComponents()
                .addSecuritySchemes(
                        "noopSignature",
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY)
                                .in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER)
                                .name("X-Noop-Signature")
                                .description("Configured Noop development-provider signature."));
        return document;
    }
}

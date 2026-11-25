package com.example.omp.adapter.rest;

import com.example.omp.shared.web.openapi.PublishedApiConfiguration;
import com.example.omp.shared.web.openapi.PublishedApiSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(PublishedApiConfiguration.class)
public class OpenApiConfig {
    @Bean
    public OpenApiCustomizer bankingProblemResponses() {
        return document -> {
            for (var path : document.getPaths().values()) {
                for (var operation : path.readOperations()) {
                    // Two advice methods contribute these responses; their reflection
                    // order differs between runtimes, so publish one stable description.
                    operation
                            .getResponses()
                            .get("409")
                            .setDescription("Conflicting transfer identity");
                    operation
                            .getResponses()
                            .get("503")
                            .setDescription("Banking service unavailable");
                    for (String status : List.of("401", "403")) {
                        var schema = new Schema<>().$ref("#/components/schemas/ProblemDetail");
                        var content =
                                new Content()
                                        .addMediaType(
                                                "application/problem+json",
                                                new MediaType().schema(schema));
                        operation.getResponses().get(status).setContent(content);
                    }
                }
            }
        };
    }

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

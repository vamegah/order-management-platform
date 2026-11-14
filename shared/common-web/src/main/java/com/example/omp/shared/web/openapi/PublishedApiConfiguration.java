package com.example.omp.shared.web.openapi;

import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Derives operation identity and a complete route inventory from the running application. No
 * checked-in contract is read by the producer, so changing a fixture cannot hide drift.
 */
@Configuration(proxyBeanMethods = false)
public class PublishedApiConfiguration {
    @Bean
    public OpenApiCustomizer publishedRouteInventory(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        return document -> {
            var routes = new TreeSet<String>();
            mappings.getHandlerMethods()
                    .forEach(
                            (mapping, handler) -> {
                                for (String path : mapping.getPatternValues()) {
                                    if (!path.startsWith("/api/")) continue;
                                    if (mapping.getMethodsCondition().getMethods().isEmpty()) {
                                        throw new IllegalStateException(
                                                "Public API routes must declare HTTP methods");
                                    }
                                    mapping.getMethodsCondition()
                                            .getMethods()
                                            .forEach(
                                                    method ->
                                                            routes.add(method.name() + " " + path));
                                }
                            });
            document.addExtension("x-implemented-routes", routes.stream().toList());
        };
    }

    @Bean
    public OperationCustomizer publishedOperationIdentity() {
        return (operation, handler) -> {
            var annotation =
                    handler.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
            if (annotation == null || annotation.operationId().isBlank()) {
                operation.setOperationId(
                        handler.getBeanType().getSimpleName().replace("Controller", "")
                                + "_"
                                + handler.getMethod().getName());
            }
            var policy = handler.getMethodAnnotation(PreAuthorize.class);
            if (policy == null) {
                policy =
                        AnnotatedElementUtils.findMergedAnnotation(
                                handler.getBeanType(), PreAuthorize.class);
            }
            if (policy != null) operation.addExtension("x-method-authorization", policy.value());
            if (operation.getResponses() == null) operation.setResponses(new ApiResponses());
            operation
                    .getResponses()
                    .putIfAbsent(
                            "401",
                            new ApiResponse()
                                    .description("Missing or invalid authentication credentials."));
            operation
                    .getResponses()
                    .putIfAbsent(
                            "403",
                            new ApiResponse()
                                    .description(
                                            "The authenticated identity is not authorized for this operation."));
            return operation;
        };
    }
}

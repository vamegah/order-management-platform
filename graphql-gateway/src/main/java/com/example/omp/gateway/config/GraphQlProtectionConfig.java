package com.example.omp.gateway.config;

import graphql.GraphqlErrorBuilder;
import graphql.analysis.*;
import graphql.execution.AbortExecutionException;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration
public class GraphQlProtectionConfig {

    @Bean
    public graphql.execution.instrumentation.Instrumentation callerContextPropagation() {
        return new graphql.execution.instrumentation.SimplePerformantInstrumentation() {
            @Override
            public graphql.ExecutionInput instrumentExecutionInput(
                    graphql.ExecutionInput input,
                    graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
                            parameters,
                    graphql.execution.instrumentation.InstrumentationState state) {
                var authentication =
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .getAuthentication();
                if (authentication != null) {
                    var snapshot =
                            org.springframework.security.core.context.SecurityContextHolder
                                    .createEmptyContext();
                    snapshot.setAuthentication(authentication);
                    input.getGraphQLContext()
                            .put(
                                    org.springframework.security.core.context.SecurityContext.class
                                            .getName(),
                                    snapshot);
                }
                return input;
            }
        };
    }

    @Bean
    public org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer
            annotatedControllerConfigurer(
                    @org.springframework.beans.factory.annotation.Qualifier(
                                    "applicationTaskExecutor")
                            java.util.concurrent.Executor executor,
                    org.springframework.beans.factory.ListableBeanFactory beanFactory) {
        var configurer =
                new org.springframework.graphql.data.method.annotation.support
                        .AnnotatedControllerConfigurer();
        // A plain Executor enables async invocation of blocking controller methods; Spring's
        // pool executor otherwise advertises a preference for short-lived synchronous tasks.
        configurer.setExecutor(executor::execute);
        configurer.addFormatterRegistrar(
                registry ->
                        org.springframework.boot.convert.ApplicationConversionService.addBeans(
                                registry, beanFactory));
        return configurer;
    }

    @Bean
    public MaxQueryDepthInstrumentation queryDepthLimit(GraphQlProtectionProperties limits) {
        return new MaxQueryDepthInstrumentation(limits.maxDepth()) {
            @Override
            protected AbortExecutionException mkAbortException(int depth, int maximum) {
                return rejected("QUERY_DEPTH_LIMIT", "Query exceeds the maximum depth");
            }
        };
    }

    @Bean
    public MaxQueryComplexityInstrumentation queryComplexityLimit(
            GraphQlProtectionProperties limits) {
        return new MaxQueryComplexityInstrumentation(
                limits.maxComplexity(),
                (environment, children) -> cost(environment, children, limits.maxComplexity())) {
            @Override
            protected AbortExecutionException mkAbortException(int cost, int maximum) {
                return rejected("QUERY_COMPLEXITY_LIMIT", "Query exceeds the maximum complexity");
            }
        };
    }

    private static int cost(FieldComplexityEnvironment environment, int children, int maximum) {
        var arguments = environment.getArguments();
        Object size = arguments.get("size");
        Object limit = arguments.get("limit");
        long multiplier =
                size instanceof Number number
                        ? Math.max(1, number.longValue())
                        : limit instanceof Number number
                                ? Math.max(1, number.longValue())
                                : Set.of("products", "customers", "orders")
                                                .contains(environment.getField().getName())
                                        ? 20
                                        : 1;
        return (int) Math.min(maximum + 1L, (1L + children) * multiplier);
    }

    private static AbortExecutionException rejected(String code, String message) {
        return new AbortExecutionException(
                List.of(
                        GraphqlErrorBuilder.newError()
                                .message(message)
                                .extensions(Map.of("code", code))
                                .build()));
    }
}

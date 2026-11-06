package com.example.omp.gateway.config;

import graphql.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import org.springframework.graphql.server.*;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Bounds the response budget; cancellation does not undo already-started mutations. */
@Component
public class GraphQlTimeoutInterceptor implements WebGraphQlInterceptor {
    private final GraphQlProtectionProperties limits;

    public GraphQlTimeoutInterceptor(GraphQlProtectionProperties limits) {
        this.limits = limits;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (request.getExtensions().containsKey("persistedQuery"))
            return Mono.just(
                    error(
                            request,
                            "PERSISTED_QUERIES_UNSUPPORTED",
                            "Send the complete query without persisted-query extensions"));
        return Mono.defer(() -> chain.next(request))
                .timeout(limits.timeout())
                .onErrorResume(
                        TimeoutException.class,
                        failure ->
                                Mono.just(
                                        error(
                                                request,
                                                "QUERY_TIMEOUT",
                                                "Query exceeded the response time limit")));
    }

    private WebGraphQlResponse error(WebGraphQlRequest request, String code, String message) {
        return new WebGraphQlResponse(
                new DefaultExecutionGraphQlResponse(
                        request.toExecutionInput(),
                        ExecutionResultImpl.newExecutionResult()
                                .errors(
                                        List.of(
                                                GraphqlErrorBuilder.newError()
                                                        .message(message)
                                                        .extensions(Map.of("code", code))
                                                        .build()))
                                .build()));
    }
}

package com.example.omp.gateway.config;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.server.*;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

class GraphQlTimeoutInterceptorTest {

    @Test
    void persistedQueryExtensionsAreExplicitlyRejectedWithoutExecution() {
        var limits =
                new GraphQlProtectionProperties(
                        12, 2000, 1024, 10, 100, Duration.ofMinutes(1), Duration.ofSeconds(1));
        var request =
                new WebGraphQlRequest(
                        URI.create("http://localhost/graphql"),
                        new HttpHeaders(),
                        null,
                        Map.of(),
                        Map.of(
                                "query",
                                "{__typename}",
                                "extensions",
                                Map.of("persistedQuery", Map.of("sha256Hash", "hash"))),
                        "request",
                        Locale.ROOT);
        var invoked = new AtomicBoolean();
        var response =
                new GraphQlTimeoutInterceptor(limits)
                        .intercept(
                                request,
                                ignored -> {
                                    invoked.set(true);
                                    return Mono.never();
                                })
                        .block();
        assertThat(invoked).isFalse();
        assertThat(response.getErrors())
                .singleElement()
                .satisfies(
                        error ->
                                assertThat(error.getExtensions())
                                        .containsEntry("code", "PERSISTED_QUERIES_UNSUPPORTED"));
    }

    @Test
    void slowExecutionReturnsStableErrorAndCancelsTheChain() {
        var limits =
                new GraphQlProtectionProperties(
                        12, 2000, 1024, 10, 100, Duration.ofMinutes(1), Duration.ofMillis(20));
        var request =
                new WebGraphQlRequest(
                        URI.create("http://localhost/graphql"),
                        new HttpHeaders(),
                        null,
                        Map.of(),
                        Map.of("query", "{__typename}"),
                        "request",
                        Locale.ROOT);
        var cancelled = new AtomicBoolean();
        var response =
                new GraphQlTimeoutInterceptor(limits)
                        .intercept(
                                request,
                                ignored ->
                                        Mono.<WebGraphQlResponse>never()
                                                .doOnCancel(() -> cancelled.set(true)))
                        .block(Duration.ofSeconds(2));
        assertThat(response.getErrors())
                .singleElement()
                .satisfies(
                        error ->
                                assertThat(error.getExtensions())
                                        .containsEntry("code", "QUERY_TIMEOUT"));
        assertThat(cancelled).isTrue();
    }
}

package com.example.omp.gateway.config;

import static org.assertj.core.api.Assertions.*;

import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.schema.idl.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GraphQlProtectionTest {

    @Test
    void largeVariableCostsCannotOverflowWhenAliasesAreCombined() {
        var result =
                graph(12, 10)
                        .execute(
                                "{first:items(size:2147483647){id} second:items(size:2147483647){id}}");
        assertThat(result.getErrors())
                .singleElement()
                .satisfies(
                        error ->
                                assertThat(error.getExtensions())
                                        .containsEntry("code", "QUERY_COMPLEXITY_LIMIT"));
        assertThat(fetches).hasValue(0);
    }

    private final AtomicInteger fetches = new AtomicInteger();

    private GraphQL graph(int depth, int complexity) {
        var limits =
                new GraphQlProtectionProperties(
                        depth,
                        complexity,
                        1024,
                        10,
                        100,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1));
        var config = new GraphQlProtectionConfig();
        var schema =
                new SchemaGenerator()
                        .makeExecutableSchema(
                                new SchemaParser()
                                        .parse(
                                                """
                type Query { tree: Tree items(size: Int): [Tree] }
                type Tree { id: String child: Tree }
                """),
                                RuntimeWiring.newRuntimeWiring()
                                        .type(
                                                "Query",
                                                type ->
                                                        type.dataFetcher(
                                                                        "tree",
                                                                        environment -> {
                                                                            fetches
                                                                                    .incrementAndGet();
                                                                            return tree();
                                                                        })
                                                                .dataFetcher(
                                                                        "items",
                                                                        environment -> {
                                                                            fetches
                                                                                    .incrementAndGet();
                                                                            return List.of(tree());
                                                                        }))
                                        .build());
        return GraphQL.newGraphQL(schema)
                .instrumentation(
                        new ChainedInstrumentation(
                                List.of(
                                        config.queryDepthLimit(limits),
                                        config.queryComplexityLimit(limits))))
                .build();
    }

    private Map<String, Object> tree() {
        return Map.of("id", "one", "child", Map.of("id", "two"));
    }

    @Test
    void deepFragmentsAreRejectedBeforeFetching() {
        var response =
                graph(2, 2000)
                        .execute("{ tree { ...Nested } } fragment Nested on Tree { child { id } }");
        assertThat(response.getErrors())
                .singleElement()
                .satisfies(
                        error ->
                                assertThat(error.getExtensions())
                                        .containsEntry("code", "QUERY_DEPTH_LIMIT"));
        assertThat(fetches).hasValue(0);
    }

    @Test
    void variablePageSizeCountsTowardComplexity() {
        var response =
                graph(12, 10)
                        .execute(
                                graphql.ExecutionInput.newExecutionInput()
                                        .query("query($size:Int){items(size:$size){id}} ")
                                        .variables(Map.of("size", 100))
                                        .build());
        assertThat(response.getErrors())
                .singleElement()
                .satisfies(
                        error ->
                                assertThat(error.getExtensions())
                                        .containsEntry("code", "QUERY_COMPLEXITY_LIMIT"));
        assertThat(fetches).hasValue(0);
    }

    @Test
    void aliasesCannotBypassComplexityAndNormalQueriesStillExecute() {
        var graph = graph(12, 5);
        assertThat(graph.execute("{first:tree{id} second:tree{id} third:tree{id}}").getErrors())
                .singleElement()
                .satisfies(
                        error ->
                                assertThat(error.getExtensions())
                                        .containsEntry("code", "QUERY_COMPLEXITY_LIMIT"));
        assertThat(fetches).hasValue(0);
        assertThat(graph.execute("{tree{id}}").getErrors()).isEmpty();
        assertThat(fetches).hasValue(1);
    }
}

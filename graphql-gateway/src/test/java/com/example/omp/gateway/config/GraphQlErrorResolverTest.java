package com.example.omp.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import feign.Request;
import feign.RetryableException;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphQlErrorResolverTest {
    @Test
    void networkFailureHasTheStableDownstreamCodeWithoutLeakingTheRequestOrCause() {
        var environment = mock(DataFetchingEnvironment.class);
        when(environment.getField())
                .thenReturn(
                        Field.newField("product").sourceLocation(new SourceLocation(1, 1)).build());
        when(environment.getExecutionStepInfo())
                .thenReturn(
                        ExecutionStepInfo.newExecutionStepInfo()
                                .type(Scalars.GraphQLString)
                                .path(ResultPath.rootPath().segment("product"))
                                .build());
        var request =
                Request.create(
                        Request.HttpMethod.GET,
                        "https://private-service.invalid/path?private=value",
                        Map.of("Authorization", java.util.List.of("Bearer private-token")),
                        new byte[0],
                        StandardCharsets.UTF_8,
                        null);
        var failure =
                new RetryableException(
                        -1, "private socket failure", Request.HttpMethod.GET, (Long) null, request);
        var error = new GraphQlErrorResolver().resolveToSingleError(failure, environment);
        assertThat(error.getExtensions()).containsEntry("code", "DOWNSTREAM_SERVICE_ERROR");
        assertThat(error.getMessage())
                .isEqualTo("Request could not be completed")
                .doesNotContain("private", "Bearer", "invalid");
    }
}

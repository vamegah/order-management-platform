package com.example.omp.gateway.graphql.error;

import com.example.omp.gateway.error.*;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Translates exceptions from resolvers into GraphQL errors with
 * structured extensions.
 *
 * <p>The `extensions.code` field maps to our platform error vocabulary
 * (NOT_FOUND, CONFLICT, etc.), which clients can switch on.
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ClientNotFoundException) {
            return error("NOT_FOUND", ex.getMessage(), env);
        }
        if (ex instanceof ClientConflictException) {
            return error("CONFLICT", ex.getMessage(), env);
        }
        if (ex instanceof ClientValidationException) {
            return error("VALIDATION_FAILED", ex.getMessage(), env);
        }
        if (ex instanceof DownstreamServiceException) {
            return error("DOWNSTREAM_SERVICE_ERROR", ex.getMessage(), env);
        }
        if (ex instanceof AccessDeniedException) {
            return error("FORBIDDEN", "Access denied", env);
        }
        // Fallback
        return error("INTERNAL_ERROR", "An unexpected error occurred", env);
    }

    private GraphQLError error(String code, String message, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
            .message(message)
            .extensions(Map.of("code", code))
            .build();
    }
}
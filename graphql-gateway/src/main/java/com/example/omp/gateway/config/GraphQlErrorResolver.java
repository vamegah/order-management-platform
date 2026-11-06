package com.example.omp.gateway.config;

import com.example.omp.gateway.graphql.error.*;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class GraphQlErrorResolver extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(
            Throwable failure, DataFetchingEnvironment environment) {
        String code =
                failure instanceof ClientNotFoundException
                        ? "NOT_FOUND"
                        : failure instanceof ClientConflictException
                                ? "CONFLICT"
                                : failure instanceof AccessDeniedException
                                        ? "FORBIDDEN"
                                        : failure instanceof ClientValidationException
                                                        || failure
                                                                instanceof IllegalArgumentException
                                                        || failure
                                                                instanceof
                                                                ConstraintViolationException
                                                ? "VALIDATION_FAILED"
                                                : failure instanceof DownstreamServiceException
                                                        ? "DOWNSTREAM_SERVICE_ERROR"
                                                        : "INTERNAL_ERROR";
        return GraphqlErrorBuilder.newError(environment)
                .message(
                        switch (code) {
                            case "NOT_FOUND" -> "Resource not found";
                            case "FORBIDDEN" -> "Access denied";
                            case "VALIDATION_FAILED" -> "Invalid request";
                            case "CONFLICT" -> "Resource conflict";
                            default -> "Request could not be completed";
                        })
                .extensions(Map.of("code", code))
                .build();
    }
}

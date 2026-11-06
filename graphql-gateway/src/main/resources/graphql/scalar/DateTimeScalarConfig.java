package com.example.omp.gateway.graphql.scalar;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Registers the {@code DateTime} scalar for GraphQL.
 *
 * <p>Maps between GraphQL's String representation and Java's {@link Instant}.
 *
 * <p><b>Format:</b> ISO 8601 with UTC offset, as produced by
 * {@link DateTimeFormatter#ISO_INSTANT}. Examples:
 * <ul>
 *   <li>{@code "2025-01-15T14:23:45Z"}</li>
 *   <li>{@code "2025-01-15T14:23:45.123Z"}</li>
 *   <li>{@code "2025-01-15T14:23:45.123456789Z"}</li>
 * </ul>
 *
 * <p>Serialization is always in UTC with a {@code Z} suffix. Client-supplied
 * offsets are honored during parsing but normalized to UTC internally.
 */
@Configuration
public class DateTimeScalarConfig {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;

    @Bean
    public RuntimeWiringConfigurer dateTimeScalarConfigurer() {
        return wiringBuilder -> wiringBuilder.scalar(dateTimeScalar());
    }

    private GraphQLScalarType dateTimeScalar() {
        return GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("An ISO 8601 timestamp with UTC offset. Example: \"2025-01-15T14:23:45.123Z\"")
            .coercing(new Coercing<Instant, String>() {

                // ---- Java → GraphQL ----------------------------------------------

                @Override
                public String serialize(Object dataFetcherResult,
                                        GraphQLContext ctx,
                                        Locale locale) {
                    if (dataFetcherResult instanceof Instant instant) {
                        return FORMATTER.format(instant);
                    }
                    if (dataFetcherResult instanceof String s) {
                        // Allow resolvers to return pre-formatted strings
                        // (useful when forwarding a downstream value verbatim).
                        return s;
                    }
                    throw new CoercingSerializeException(
                        "Cannot serialize to DateTime: " + describe(dataFetcherResult));
                }

                // ---- GraphQL → Java ----------------------------------------------

                @Override
                public Instant parseValue(Object input,
                                          GraphQLContext ctx,
                                          Locale locale) {
                    if (input instanceof String s) {
                        try {
                            return Instant.parse(s);
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseValueException(
                                "Invalid DateTime value: '" + s + "'. Expected ISO 8601 with offset.", e);
                        }
                    }
                    throw new CoercingParseValueException(
                        "Expected a String for DateTime but got: " + describe(input));
                }

                // ---- GraphQL literal → Java --------------------------------------

                @Override
                public Instant parseLiteral(Value<?> input,
                                            CoercedVariables variables,
                                            GraphQLContext ctx,
                                            Locale locale) {
                    if (input instanceof StringValue sv) {
                        try {
                            return Instant.parse(sv.getValue());
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseLiteralException(
                                "Invalid DateTime literal: '" + sv.getValue() + "'.", e);
                        }
                    }
                    throw new CoercingParseLiteralException(
                        "Expected a String literal for DateTime but got: " + input);
                }

                // ---- Utility -----------------------------------------------------

                private String describe(Object obj) {
                    return obj == null ? "null" : obj.getClass().getSimpleName() + " (" + obj + ")";
                }
            })
            .build();
    }
}

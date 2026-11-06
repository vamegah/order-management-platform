package com.example.omp.gateway.graphql.scalar;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Configuration
public class DateScalarConfig {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @Bean
    public RuntimeWiringConfigurer dateScalarConfigurer() {
        return wiringBuilder -> wiringBuilder.scalar(dateScalar());
    }

    private GraphQLScalarType dateScalar() {
        return GraphQLScalarType.newScalar()
            .name("Date")
            .description("An ISO 8601 date (yyyy-MM-dd).")
            .coercing(new Coercing<LocalDate, String>() {

                @Override
                public String serialize(Object dataFetcherResult, GraphQLContext ctx, Locale locale) {
                    if (dataFetcherResult instanceof LocalDate d) return FORMATTER.format(d);
                    if (dataFetcherResult instanceof String s) return s;
                    throw new CoercingSerializeException("Not a Date: " + dataFetcherResult);
                }

                @Override
                public LocalDate parseValue(Object input, GraphQLContext ctx, Locale locale) {
                    if (input instanceof String s) return LocalDate.parse(s, FORMATTER);
                    throw new CoercingParseValueException("Not a Date string: " + input);
                }

                @Override
                public LocalDate parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext ctx, Locale locale) {
                    if (input instanceof StringValue sv) return LocalDate.parse(sv.getValue(), FORMATTER);
                    throw new CoercingParseLiteralException("Not a Date literal: " + input);
                }
            })
            .build();
    }
}

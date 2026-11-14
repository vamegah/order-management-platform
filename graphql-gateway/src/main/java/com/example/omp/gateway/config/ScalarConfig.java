package com.example.omp.gateway.config;

import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.*;
import java.math.BigDecimal;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class ScalarConfig {
    @Bean
    public RuntimeWiringConfigurer scalarWiring() {
        return wiring -> {
            for (String name : new String[] {"Date", "DateTime", "Decimal"}) {
                wiring.scalar(
                        GraphQLScalarType.newScalar()
                                .name(name)
                                .coercing(
                                        new Coercing<Object, String>() {
                                            @Override
                                            public String serialize(Object value) {
                                                return value instanceof BigDecimal decimal
                                                        ? decimal.toPlainString()
                                                        : value.toString();
                                            }

                                            @Override
                                            public Object parseValue(Object value) {
                                                try {
                                                    return switch (name) {
                                                        case "Date" ->
                                                                LocalDate.parse(value.toString());
                                                        case "DateTime" ->
                                                                OffsetDateTime.parse(
                                                                                value.toString())
                                                                        .toInstant();
                                                        default -> new BigDecimal(value.toString());
                                                    };
                                                } catch (RuntimeException failure) {
                                                    throw new CoercingParseValueException(
                                                            "Invalid " + name);
                                                }
                                            }

                                            @Override
                                            public Object parseLiteral(Object value) {
                                                try {
                                                    if (value instanceof StringValue text)
                                                        return parseValue(text.getValue());
                                                    if (name.equals("Decimal")
                                                            && value instanceof IntValue number)
                                                        return parseValue(number.getValue());
                                                    if (name.equals("Decimal")
                                                            && value instanceof FloatValue number)
                                                        return parseValue(number.getValue());
                                                } catch (CoercingParseValueException failure) {
                                                    throw new CoercingParseLiteralException(
                                                            "Invalid " + name);
                                                }
                                                throw new CoercingParseLiteralException(
                                                        "Invalid " + name);
                                            }
                                        })
                                .build());
            }
        };
    }
}

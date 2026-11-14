package com.example.omp.gateway.config;

import static org.assertj.core.api.Assertions.*;

import graphql.language.StringValue;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScalarContractTest {
    private GraphQLScalarType scalar(String name) {
        var wiring = RuntimeWiring.newRuntimeWiring();
        new ScalarConfig().scalarWiring().configure(wiring);
        return wiring.build().getScalars().get(name);
    }

    @Test
    void decimalOutputIsPlainTextAndNumericInputsRemainSupported() {
        var decimal = scalar("Decimal").getCoercing();
        assertThat(decimal.serialize(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat((BigDecimal) decimal.parseValue("1.25")).isEqualByComparingTo("1.25");
        assertThat((BigDecimal) decimal.parseValue(12)).isEqualByComparingTo("12");
    }

    @Test
    void temporalInputsNormalizeToTheDocumentedTypes() {
        assertThat(scalar("Date").getCoercing().parseValue("1990-01-02"))
                .isEqualTo(LocalDate.of(1990, 1, 2));
        assertThat(scalar("DateTime").getCoercing().parseValue("2026-11-13T12:00:00+01:00"))
                .isEqualTo(Instant.parse("2026-11-13T11:00:00Z"));
        assertThat(scalar("Date").getCoercing().serialize(LocalDate.of(1990, 1, 2)))
                .isEqualTo("1990-01-02");
    }

    @Test
    void invalidVariablesUseGraphqlCoercionErrorsWithoutTheRejectedValue() {
        for (String name : new String[] {"Date", "DateTime", "Decimal"}) {
            assertThatThrownBy(() -> scalar(name).getCoercing().parseValue("private-invalid-input"))
                    .isInstanceOf(CoercingParseValueException.class)
                    .hasMessage("Invalid " + name);
        }
    }

    @Test
    void invalidLiteralsUseLiteralCoercionErrors() {
        assertThatThrownBy(
                        () ->
                                scalar("Date")
                                        .getCoercing()
                                        .parseLiteral(
                                                StringValue.newStringValue("invalid").build()))
                .isInstanceOf(CoercingParseLiteralException.class)
                .hasMessage("Invalid Date");
    }
}

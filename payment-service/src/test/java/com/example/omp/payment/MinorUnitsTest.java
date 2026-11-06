package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.domain.MinorUnits;
import com.example.omp.shared.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MinorUnitsTest {
    @ParameterizedTest
    @CsvSource({"USD,1234,12.34", "JPY,1234,1234", "KWD,1234,1.234"})
    void preservesCurrencySpecificPrecision(String currency, long minor, String decimal) {
        var money = MinorUnits.toMoney(minor, currency);
        assertThat(money.getAmount()).isEqualByComparingTo(decimal);
        assertThat(MinorUnits.fromMoney(money)).isEqualTo(minor);
    }

    @Test
    void rejectsFractionalMinorUnits() {
        assertThatThrownBy(
                        () ->
                                MinorUnits.fromMoney(
                                        Money.of(
                                                new BigDecimal("1.001"),
                                                Currency.getInstance("USD"))))
                .isInstanceOf(ArithmeticException.class);
    }
}

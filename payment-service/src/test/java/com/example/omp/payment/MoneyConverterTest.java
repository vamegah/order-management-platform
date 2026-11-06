package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.domain.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyConverterTest {
    @ParameterizedTest
    @CsvSource({"10.00,USD,1000", "10,JPY,10", "1.234,KWD,1234"})
    void preservesExactCurrencyUnits(String amount, String currency, long minor) {
        var money =
                new MoneyConverter()
                        .toDomain(
                                com.example.omp.proto.common.Money.newBuilder()
                                        .setAmount(amount)
                                        .setCurrency(currency)
                                        .build());
        assertThat(MinorUnits.fromMoney(money)).isEqualTo(minor);
    }

    @ParameterizedTest
    @CsvSource({
        "1.001,USD",
        "1.5,JPY",
        "1.2345,KWD",
        "1.00001,USD",
        "1e100000000,USD",
        "-1,USD",
        "0,USD",
        "1000000000000000,USD",
        "1,XXX"
    })
    void rejectsInvalidAmountsBeforeRounding(String amount, String currency) {
        assertThatThrownBy(
                        () ->
                                new MoneyConverter()
                                        .toDomain(
                                                com.example.omp.proto.common.Money.newBuilder()
                                                        .setAmount(amount)
                                                        .setCurrency(currency)
                                                        .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.example.omp.shared.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void tinyExponentInputsRoundToZeroWithoutHugeIntermediatePowers() {
        assertThat(Money.usd("1E-2147483647").getAmount()).isEqualByComparingTo("0.0000");
        assertThat(Money.usd("0.0000499999").getAmount()).isEqualByComparingTo("0.0000");
        assertThat(Money.usd("0.00005").getAmount()).isEqualByComparingTo("0.0001");
    }

    @Test
    void storagePrecisionAppliesToFactoriesAndEveryArithmeticResult() {
        var maximum = Money.usd("999999999999999.9999");
        assertThat(maximum.getAmount()).isEqualByComparingTo("999999999999999.9999");
        assertThat(Money.usd("999999999999999.99994")).isEqualTo(maximum);
        for (String value :
                new String[] {"1000000000000000", "999999999999999.99995", "1E+1000000"}) {
            assertThatThrownBy(() -> Money.usd(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("precision");
        }
        assertThatThrownBy(() -> maximum.add(Money.usd("0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> maximum.multiply(BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> maximum.subtract(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("other");
    }

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    @Test
    void ofCreatesMoneyWithScaleFour() {
        Money m = Money.of(new BigDecimal("9.9"), USD);
        assertThat(m.getAmount()).isEqualByComparingTo("9.9000");
        assertThat(m.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void ofRejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("-1"), USD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void ofUsesDefaultCurrencyAndRejectsNullInputs() {
        assertThat(Money.of(new BigDecimal("1")).getCurrency()).isEqualTo(USD);
        assertThatThrownBy(() -> Money.of(null, USD)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void usdConvenienceFactoryUsesUsd() {
        Money m = Money.usd("19.99");
        assertThat(m.getCurrencyCode()).isEqualTo("USD");
        assertThat(m.getAmount()).isEqualByComparingTo("19.9900");
    }

    // -------------------------------------------------------------------------
    // Arithmetic
    // -------------------------------------------------------------------------

    @Test
    void addProducesSumWhenCurrenciesMatch() {
        Money a = Money.usd("10.00");
        Money b = Money.usd("5.50");
        assertThat(a.add(b).getAmount()).isEqualByComparingTo("15.5000");
    }

    @Test
    void addRejectsDifferentCurrencies() {
        Money a = Money.usd("10.00");
        Money b = Money.of(new BigDecimal("5"), EUR);
        assertThatThrownBy(() -> a.add(b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void subtractRejectsNegativeResult() {
        Money a = Money.usd("5.00");
        Money b = Money.usd("10.00");
        assertThatThrownBy(() -> a.subtract(b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void subtractProducesDifferenceWhenCurrenciesMatch() {
        assertThat(Money.usd("10.00").subtract(Money.usd("4.25")).getAmount())
                .isEqualByComparingTo("5.7500");
    }

    @Test
    void multiplyScalesAmount() {
        Money m = Money.usd("10.00");
        assertThat(m.multiply(new BigDecimal("2.5")).getAmount()).isEqualByComparingTo("25.0000");
    }

    @Test
    void multiplyRejectsNegativeFactor() {
        Money m = Money.usd("10.00");
        assertThatThrownBy(() -> m.multiply(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiplyRejectsNullFactor() {
        assertThatThrownBy(() -> Money.usd("10.00").multiply(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    void equalAmountsAndCurrenciesAreEqual() {
        assertThat(Money.usd("10.00")).isEqualTo(Money.usd("10.0000"));
    }

    @Test
    void sameAmountDifferentCurrencyIsNotEqual() {
        assertThat(Money.usd("10.00")).isNotEqualTo(Money.of(new BigDecimal("10.00"), EUR));
    }

    @Test
    void equalityHandlesIdentityAndOtherTypesAndHashCodeIgnoresScale() {
        Money money = Money.usd("10.00");
        assertThat(money).isEqualTo(money).isNotEqualTo("10.00");
        assertThat(money).hasSameHashCodeAs(Money.usd("10.0000"));
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    void toStringProducesHumanReadableForm() {
        assertThat(Money.usd("10.00").toString()).isEqualTo("10.0000 USD");
    }
}

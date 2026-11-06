package com.example.omp.payment.domain;

import com.example.omp.shared.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;

/** Exact conversion at provider boundaries; fractional minor units are rejected. */
public final class MinorUnits {
    private MinorUnits() {}

    public static Money toMoney(long amount, String code) {
        Currency currency = Currency.getInstance(code);
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Unsupported currency: " + code);
        return Money.of(BigDecimal.valueOf(amount, scale), currency);
    }

    public static long fromMoney(Money money) {
        int scale = money.getCurrency().getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Unsupported currency");
        return money.getAmount().movePointRight(scale).longValueExact();
    }
}

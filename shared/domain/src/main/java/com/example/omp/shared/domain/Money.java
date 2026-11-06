package com.example.omp.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount in a specific currency.
 *
 * <p>Value object. Immutable. Two {@code Money} values are only equal if their amounts <em>and</em>
 * currencies match.
 *
 * <p>Embedded into owning entities as amount and currency columns using JPA attribute overrides.
 *
 * <p>Why not just {@code BigDecimal}? Because {@code BigDecimal} doesn't know it's money. Nothing
 * prevents {@code price.add(taxRate)} if both are {@code BigDecimal}. This type makes such mistakes
 * compile errors.
 */
@Embeddable
public class Money {

    /** Scale for amounts: 4 decimal places, matching numeric(19,4). */
    public static final int SCALE = 4;

    private static final BigDecimal MAX_EXCLUSIVE = new BigDecimal("1000000000000000");
    private static final BigDecimal HALF_SMALLEST_UNIT = new BigDecimal("0.00005");

    /**
     * Currency used when none is specified. Single-currency deployments default to USD;
     * multi-currency ones always specify explicitly.
     */
    public static final Currency DEFAULT_CURRENCY = Currency.getInstance("USD");

    @NotNull
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @NotBlank
    @Column(nullable = false, length = 3)
    private String currency;

    /** JPA no-arg constructor. Not for application use. */
    protected Money() {}

    private Money(BigDecimal amount, String currencyCode) {
        if (amount.compareTo(MAX_EXCLUSIVE) >= 0) {
            throw new IllegalArgumentException("Money amount exceeds numeric(19,4) precision");
        }
        // Avoid constructing an enormous power of ten for tiny exponent-form inputs.
        this.amount =
                amount.compareTo(HALF_SMALLEST_UNIT) < 0
                        ? BigDecimal.ZERO.setScale(SCALE)
                        : amount.setScale(SCALE, RoundingMode.HALF_UP);
        if (this.amount.precision() > 19) {
            throw new IllegalArgumentException(
                    "Money amount exceeds numeric(19,4) precision after rounding");
        }
        this.currency = currencyCode;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /** Creates money in the default currency (USD). */
    public static Money of(BigDecimal amount) {
        return of(amount, DEFAULT_CURRENCY);
    }

    public static Money zero(String currency) {
        return of(BigDecimal.ZERO, Currency.getInstance(currency));
    }

    /** Creates money in the given currency. */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative: " + amount);
        }
        return new Money(amount, currency.getCurrencyCode());
    }

    /** Convenience factory for USD amounts from strings. */
    public static Money usd(String amount) {
        return of(new BigDecimal(amount), DEFAULT_CURRENCY);
    }

    // -------------------------------------------------------------------------
    // Arithmetic — only valid between same-currency values
    // -------------------------------------------------------------------------

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        BigDecimal result = this.amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new IllegalArgumentException("Subtraction result would be negative: " + result);
        }
        return new Money(result, this.currency);
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        if (factor.signum() < 0) {
            throw new IllegalArgumentException("Multiplier cannot be negative: " + factor);
        }
        return new Money(this.amount.multiply(factor), this.currency);
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currency);
    }

    public String getCurrencyCode() {
        return currency;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return amount.compareTo(other.amount) == 0 && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}

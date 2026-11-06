package com.example.omp.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Year;
import java.util.Objects;

/**
 * Business identifier for an order.
 *
 * <p>Format: {@code ORD-YYYY-NNNNNNN}. Human-readable, unique, intended to be shared with customers
 * (support tickets, confirmation emails).
 *
 * <p>Distinct from the UUID {@code id}, which is an internal identifier.
 *
 * <p>Value object. Stored as a single column, {@code order_number}.
 */
@Embeddable
public class OrderNumber {

    private static final String FORMAT_PATTERN = "^ORD-\\d{4}-\\d{7}$";

    @NotBlank
    @Pattern(regexp = FORMAT_PATTERN, message = "Order number must match ORD-YYYY-NNNNNNN")
    @Column(name = "order_number", nullable = false, length = 30)
    private String value;

    protected OrderNumber() {}

    private OrderNumber(String value) {
        this.value = value;
    }

    /**
     * Creates an order number from a year and sequence.
     *
     * @param year e.g., 2025
     * @param sequence e.g., 1234 (padded to 7 digits)
     */
    public static OrderNumber from(int year, long sequence) {
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("Year out of range: " + year);
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("Sequence must be positive: " + sequence);
        }
        return new OrderNumber(String.format("ORD-%d-%07d", year, sequence));
    }

    /** Convenience: creates from a sequence for the current year. */
    public static OrderNumber next(long sequence) {
        return from(Year.now().getValue(), sequence);
    }

    /** Parses an existing order number string (e.g., from a request). */
    public static OrderNumber of(String raw) {
        Objects.requireNonNull(raw, "orderNumber");
        String trimmed = raw.trim().toUpperCase();
        if (!trimmed.matches(FORMAT_PATTERN)) {
            throw new IllegalArgumentException("Invalid order number format: " + raw);
        }
        return new OrderNumber(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderNumber other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

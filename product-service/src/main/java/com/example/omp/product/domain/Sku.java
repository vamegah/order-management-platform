package com.example.omp.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import java.util.Objects;

/**
 * Stock Keeping Unit — the business identifier for a product.
 *
 * <p>Value object. Format: 4–64 characters, uppercase letters, digits, hyphens, underscores.
 * Case-insensitive — "SKU-001" and "sku-001" are the same SKU.
 *
 * <p>Stored as a {@code citext} column (see V1__initial_schema.sql), which enforces the
 * case-insensitivity at the database level too.
 */
@Embeddable
public class Sku {

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 64;
    private static final java.util.regex.Pattern FORMAT =
            java.util.regex.Pattern.compile(
                    "^[A-Z0-9][A-Z0-9_-]{" + (MIN_LENGTH - 1) + "," + (MAX_LENGTH - 1) + "}$");

    @NotBlank
    @Pattern(
            regexp = "^[A-Z0-9][A-Z0-9_-]{3,63}$",
            message =
                    "SKU must start with an uppercase letter or digit and contain only uppercase letters, digits, hyphens, and underscores (4-64 chars)")
    @Column(name = "sku", nullable = false, length = MAX_LENGTH, columnDefinition = "citext")
    private String value;

    protected Sku() {}

    private Sku(String value) {
        this.value = value;
    }

    /**
     * Creates a SKU from a string. The input is normalized to uppercase; validation is against the
     * normalized form.
     *
     * @throws IllegalArgumentException if the input doesn't match the format
     */
    public static Sku of(String raw) {
        Objects.requireNonNull(raw, "SKU cannot be null");
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SKU format: '"
                            + raw
                            + "'. Expected 4-64 chars, "
                            + "starting with an uppercase letter or digit, "
                            + "containing only A-Z, 0-9, -, _.");
        }
        return new Sku(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Sku other)) return false;
        return value.equalsIgnoreCase(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}

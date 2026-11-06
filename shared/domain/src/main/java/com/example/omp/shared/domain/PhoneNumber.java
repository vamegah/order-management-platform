package com.example.omp.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A customer phone number. Value object.
 *
 * <p>Phase 1 stores phone numbers as provided, with only basic sanity checks. A future migration
 * will add E.164 normalization and validation.
 *
 * <p>Nullable on the owning customer: not all customers provide a phone number.
 */
@Embeddable
public class PhoneNumber {

    // Loose pattern: at least 7 digits, plus punctuation/spaces/dashes/plus.
    // Not full E.164 (which requires + and country code) — that's a future
    // tightening.
    private static final Pattern LOOSE_PATTERN = Pattern.compile("\\+?[0-9][0-9 ().-]*[0-9]");

    private static final int MAX_LENGTH = 40;

    @Column(name = "phone", length = MAX_LENGTH)
    private String value;

    protected PhoneNumber() {}

    private PhoneNumber(String value) {
        this.value = value;
    }

    /**
     * Creates a PhoneNumber from a raw string. Accepts international and national formats.
     * Preserves the input's formatting.
     *
     * @throws IllegalArgumentException if the input is malformed
     */
    public static PhoneNumber of(String raw) {
        Objects.requireNonNull(raw, "phone cannot be null");
        String trimmed = raw.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("phone cannot be empty");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("phone exceeds " + MAX_LENGTH + " characters");
        }
        if (!LOOSE_PATTERN.matcher(trimmed).matches()
                || trimmed.chars().filter(character -> character >= '0' && character <= '9').count()
                        < 7) {
            throw new IllegalArgumentException(
                    "Invalid phone format: at least seven digits are required");
        }
        return new PhoneNumber(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhoneNumber other)) return false;
        return Objects.equals(value, other.value);
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

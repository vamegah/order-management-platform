package com.example.omp.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A customer email address. Value object.
 *
 * <p>Normalizes to lowercase for equality and storage. The {@code citext} column type in Postgres
 * (see V1__initial_schema.sql) enforces case-insensitivity at the database level too.
 *
 * <p>Validation is deliberately simpler than RFC 5322 — real-world emails are more permissive than
 * the spec. The goal is to catch obvious errors (missing @, missing TLD), not to enforce the full
 * grammar.
 */
@Embeddable
public class Email {

    // Simplified pattern: local@domain.tld
    // Allows most real addresses, rejects obvious typos.
    private static final Pattern PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final int MAX_LENGTH = 254; // RFC 5321

    @NotBlank
    @Column(name = "email", nullable = false)
    private String value;

    protected Email() {}

    private Email(String value) {
        this.value = value;
    }

    /**
     * Creates an Email, normalizing to lowercase and validating format.
     *
     * @throws IllegalArgumentException if the input is malformed
     */
    public static Email of(String raw) {
        Objects.requireNonNull(raw, "email cannot be null");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("email cannot be empty");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("email exceeds " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid email format");
        }
        String local = normalized.substring(0, normalized.indexOf('@'));
        String domain = normalized.substring(normalized.indexOf('@') + 1);
        if (local.length() > 64
                || local.startsWith(".")
                || local.endsWith(".")
                || local.contains("..")) {
            throw new IllegalArgumentException("Invalid email format");
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.startsWith("-")
                    || label.endsWith("-")) {
                throw new IllegalArgumentException("Invalid email format");
            }
        }
        return new Email(normalized);
    }

    public String value() {
        return value;
    }

    /** Local part (before @). */
    public String localPart() {
        return value.substring(0, value.indexOf('@'));
    }

    /** Domain part (after @). */
    public String domain() {
        return value.substring(value.indexOf('@') + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Email other)) return false;
        return value.equalsIgnoreCase(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}

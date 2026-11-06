package com.example.omp.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                ".alice@example.com",
                "alice.@example.com",
                "ali..ce@example.com",
                "alice@-example.com",
                "alice@example-.com",
                "alice@example..com",
                "alice@.example.com"
            })
    void rejectsInvalidLocalPartsAndDomainLabels(String email) {
        assertThatThrownBy(() -> Email.of(email)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesLocalAndDomainLabelLengthsWithoutRejectingValidSubdomains() {
        assertThatThrownBy(() -> Email.of("a".repeat(65) + "@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("alice@" + "a".repeat(64) + ".com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Email.of("a".repeat(64) + "@" + "b".repeat(63) + ".example.com").domain())
                .endsWith(".example.com");
    }

    @Test
    void normalizesWhitespaceAndCaseAndExposesParts() {
        Email email = Email.of("  Alice.Example+orders@Example.COM ");

        assertThat(email.value()).isEqualTo("alice.example+orders@example.com");
        assertThat(email.localPart()).isEqualTo("alice.example+orders");
        assertThat(email.domain()).isEqualTo("example.com");
        assertThat(email).hasToString("alice.example+orders@example.com");
    }

    @Test
    void equalityAndHashCodeAreCaseInsensitive() {
        Email first = Email.of("Alice@Example.com");
        Email second = Email.of("alice@example.COM");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isEqualTo(first).isNotEqualTo("alice@example.com");
    }

    @Test
    void rejectsNullEmptyTooLongAndMalformedAddresses() {
        assertThatThrownBy(() -> Email.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Email.of("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> Email.of("a".repeat(250) + "@x.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
        assertThatThrownBy(() -> Email.of("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid email format");
    }
}

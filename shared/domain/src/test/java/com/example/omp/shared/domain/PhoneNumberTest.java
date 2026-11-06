package com.example.omp.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNumberTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"1-----2", "123456", "123\n4567", "123\t4567", "+123\r4567"})
    void requiresSevenActualDigitsAndRejectsEmbeddedControlCharacters(String phone) {
        assertThatThrownBy(() -> PhoneNumber.of(phone))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSevenDigitsWithOptionalInternationalPrefixAndFormatting() {
        assertThat(PhoneNumber.of("123-4567").value()).isEqualTo("123-4567");
        assertThat(PhoneNumber.of("+1234567").value()).isEqualTo("+1234567");
    }

    @Test
    void preservesValidInternationalFormattingAfterTrimming() {
        PhoneNumber phone = PhoneNumber.of("  +1 (312) 555-0199 ");

        assertThat(phone.value()).isEqualTo("+1 (312) 555-0199");
        assertThat(phone).hasToString("+1 (312) 555-0199");
    }

    @Test
    void equalityAndHashCodeUseTheStoredValue() {
        PhoneNumber first = PhoneNumber.of("312-555-0199");
        PhoneNumber second = PhoneNumber.of("312-555-0199");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isEqualTo(first).isNotEqualTo("312-555-0199");
    }

    @Test
    void rejectsNullEmptyTooLongAndMalformedNumbers() {
        assertThatThrownBy(() -> PhoneNumber.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PhoneNumber.of("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> PhoneNumber.of("1".repeat(41)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
        assertThatThrownBy(() -> PhoneNumber.of("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid phone format");
    }
}

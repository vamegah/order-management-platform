package com.example.omp.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventRegistryTest {
    private final EventRegistry registry = new EventRegistry();

    @Test
    void recognizesAllCanonicalPaymentEventsAndPublishedAliases() {
        for (String event : PaymentEventType.ALL) {
            assertThat(registry.knows(event)).as(event).isTrue();
            assertThat(registry.categoryOf(event)).isEqualTo(EventRegistry.Category.PAYMENT);
        }
        assertThat(registry.knows("payment.confirmed")).isTrue();
        assertThat(registry.categoryOf("payment.confirmed"))
                .isEqualTo(EventRegistry.Category.PAYMENT);
    }

    @Test
    void toleratesMissingAndFutureTypes() {
        assertThat(registry.knows(null)).isFalse();
        assertThat(registry.knows("future.event")).isFalse();
        assertThat(registry.categoryOf(null)).isEqualTo(EventRegistry.Category.UNKNOWN);
    }
}

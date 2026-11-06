package com.example.omp.notification.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TopicTypeTest {

    @Test
    void parsesOrderDestination() {
        Optional<TopicType.ParsedDestination> parsed =
                TopicType.parse("/topic/orders/00000000-0000-0000-0000-000000000301");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().type()).isEqualTo(TopicType.ORDERS);
        assertThat(parsed.get().resourceId()).isEqualTo("00000000-0000-0000-0000-000000000301");
    }

    @Test
    void parsesPaymentDestination() {
        Optional<TopicType.ParsedDestination> parsed =
                TopicType.parse("/topic/payments/00000000-0000-0000-0000-000000000801");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().type()).isEqualTo(TopicType.PAYMENTS);
        assertThat(parsed.get().resourceId()).isEqualTo("00000000-0000-0000-0000-000000000801");
    }

    @Test
    void parsesInventoryDestination() {
        Optional<TopicType.ParsedDestination> parsed =
                TopicType.parse("/topic/inventory/LAPTOP-PRO-14");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().type()).isEqualTo(TopicType.INVENTORY);
        assertThat(parsed.get().resourceId()).isEqualTo("LAPTOP-PRO-14");
    }

    @Test
    void rejectsUnknownDestination() {
        assertThat(TopicType.parse("/topic/unknown/xyz")).isEmpty();
        assertThat(TopicType.parse("/topic/orders/not-a-uuid")).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(TopicType.parse(null)).isEmpty();
    }
}

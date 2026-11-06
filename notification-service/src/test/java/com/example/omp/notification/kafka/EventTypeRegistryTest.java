package com.example.omp.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.shared.events.InventoryEventType;
import com.example.omp.shared.events.OrderEventType;
import com.example.omp.shared.events.PaymentEventType;
import org.junit.jupiter.api.Test;

class EventTypeRegistryTest {

    private final EventTypeRegistry registry = new EventTypeRegistry();

    @Test
    void looksUpOrderEventTypes() {
        assertThat(registry.lookup(OrderEventType.ORDER_CREATED)).isNotNull();
        assertThat(registry.lookup(OrderEventType.ORDER_PLACED)).isNotNull();
        assertThat(registry.lookup(OrderEventType.ORDER_SHIPPED)).isNotNull();
    }

    @Test
    void looksUpInventoryEventTypes() {
        assertThat(registry.lookup(InventoryEventType.RESERVATION_CREATED)).isNotNull();
        assertThat(registry.lookup(InventoryEventType.RESERVATION_COMMITTED)).isNotNull();
    }

    @Test
    void looksUpPaymentEventTypes() {
        assertThat(registry.lookup(PaymentEventType.PAYMENT_INTENT_CREATED)).isNotNull();
        assertThat(registry.lookup(PaymentEventType.PAYMENT_CONFIRMED)).isNotNull();
        assertThat(registry.lookup(PaymentEventType.PAYMENT_REFUNDED)).isNotNull();
    }

    @Test
    void returnsNullForUnknownType() {
        assertThat(registry.lookup("UNKNOWN_EVENT_TYPE")).isNull();
    }
}

package com.example.omp.shared.events;

/**
 * Canonical Kafka topic names.
 *
 * <p>Every producer and consumer references topic names through these constants. This ensures
 * consistency and makes refactoring topic names a single-file change.
 *
 * <p><b>Naming convention:</b>
 *
 * <pre>
 *   &lt;domain&gt;.events.&lt;aggregate&gt;
 * </pre>
 *
 * The domain groups events by bounded context. The aggregate identifies the specific entity within
 * the domain. This allows a consumer to subscribe to all events for one aggregate type.
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // Constants only.
    }

    /** All order lifecycle events. Keyed by orderId. */
    public static final String ORDERS_EVENTS_ORDER = "orders.events.order";

    /** All inventory reservation events. Keyed by reservationId. */
    public static final String INVENTORY_EVENTS_RESERVATION = "inventory.events.reservation";

    /** All payment state-change events. Keyed by paymentId. */
    public static final String PAYMENTS_EVENTS_PAYMENT = "payments.events.payment";

    /** All topics the platform uses. Used by the TopicProvisioner. */
    public static final String[] ALL_TOPICS = {
        ORDERS_EVENTS_ORDER, INVENTORY_EVENTS_RESERVATION, PAYMENTS_EVENTS_PAYMENT
    };
}

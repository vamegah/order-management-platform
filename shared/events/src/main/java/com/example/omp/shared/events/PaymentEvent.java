package com.example.omp.shared.events;

/**
 * Base interface for all payment events.
 *
 * <p>Published to the {@code payments.events.payment} topic. Consumed by notification-service to
 * push payment status updates to customers in real time.
 *
 * <p>All payment events share:
 *
 * <ul>
 *   <li>{@code paymentId} — the aggregate ID (also the partition key).
 *   <li>{@code orderId} — the order the payment is for.
 *   <li>{@code customerId} — the customer.
 *   <li>{@code status} — the payment status after this event.
 * </ul>
 */
public interface PaymentEvent extends BaseEvent {

    /** The payment UUID. Also the partition key. */
    String paymentId();

    /** The order the payment is for. */
    String orderId();

    /** The customer UUID. */
    String customerId();

    /** Current payment status after this event. */
    String status();

    @Override
    default String aggregateId() {
        return paymentId();
    }
}

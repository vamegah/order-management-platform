package com.example.omp.payment.events;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.Refund;
import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.KafkaTopics;
import org.springframework.stereotype.Component;

@Component
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class PaymentEventPublisher {

    private final com.example.omp.shared.events.outbox.OutboxWriter outbox;
    private final com.example.omp.payment.grpc.handler.PaymentEventStreamHandler streams;

    public PaymentEventPublisher(
            com.example.omp.shared.events.outbox.OutboxWriter outbox,
            com.example.omp.payment.grpc.handler.PaymentEventStreamHandler streams) {
        this.outbox = outbox;
        this.streams = streams;
    }

    private void publish(BaseEvent event) {
        outbox.write(event, KafkaTopics.PAYMENTS_EVENTS_PAYMENT);
    }

    public void publishIntentCreated(Payment payment) {
        publish(PaymentEventFactory.intentCreated(payment));
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_UNSPECIFIED,
                "intentCreated");
    }

    public void publishConfirmed(Payment payment) {
        publish(PaymentEventFactory.confirmed(payment));
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "confirmed");
    }

    public void publishCancelled(Payment payment, String reason) {
        publish(PaymentEventFactory.cancelled(payment, reason));
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                reason);
    }

    public void publishFailed(Payment payment, String reason) {
        publish(PaymentEventFactory.failed(payment, reason));
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                reason);
    }

    /**
     * Emits the correct refund event based on the payment's resulting status: {@code
     * PaymentRefunded} if the payment is now fully REFUNDED, or {@code PaymentPartiallyRefunded} if
     * it's PARTIALLY_REFUNDED.
     */
    public void publishRefund(Payment payment, Refund refund) {
        var previous =
                payment.getRefundedAmount() != null
                                && payment.getRefundedAmount()
                                                .getAmount()
                                                .compareTo(refund.getAmount().getAmount())
                                        > 0
                        ? com.example.omp.proto.payment.PaymentStatus
                                .PAYMENT_STATUS_PARTIALLY_REFUNDED
                        : com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_CONFIRMED;
        streams.publishEvent(payment, previous, "refund");
        switch (payment.getStatus()) {
            case REFUNDED -> publish(PaymentEventFactory.refunded(payment, refund));
            case PARTIALLY_REFUNDED ->
                    publish(PaymentEventFactory.partiallyRefunded(payment, refund));
            default ->
                    throw new IllegalStateException(
                            "publishRefund called with unexpected payment status: "
                                    + payment.getStatus());
        }
    }
}

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

    public PaymentEventPublisher(com.example.omp.shared.events.outbox.OutboxWriter outbox) {
        this.outbox = outbox;
    }

    private void publish(BaseEvent event) {
        outbox.write(event, KafkaTopics.PAYMENTS_EVENTS_PAYMENT);
    }

    public void publishIntentCreated(Payment payment) {
        publish(PaymentEventFactory.intentCreated(payment));
    }

    public void publishConfirmed(Payment payment) {
        publish(PaymentEventFactory.confirmed(payment));
    }

    public void publishCancelled(Payment payment, String reason) {
        publish(PaymentEventFactory.cancelled(payment, reason));
    }

    public void publishFailed(Payment payment, String reason) {
        publish(PaymentEventFactory.failed(payment, reason));
    }

    /**
     * Emits the correct refund event based on the payment's resulting status: {@code
     * PaymentRefunded} if the payment is now fully REFUNDED, or {@code PaymentPartiallyRefunded} if
     * it's PARTIALLY_REFUNDED.
     */
    public void publishRefund(Payment payment, Refund refund) {
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

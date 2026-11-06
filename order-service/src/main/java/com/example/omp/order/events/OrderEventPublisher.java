package com.example.omp.order.events;

import com.example.omp.order.domain.Order;
import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.KafkaTopics;
import com.example.omp.shared.events.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records order events in the same transaction as the aggregate change. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderEventPublisher {
    private final OutboxWriter outbox;

    public OrderEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    private void publish(BaseEvent event) {
        outbox.write(event, KafkaTopics.ORDERS_EVENTS_ORDER);
    }

    public void publishCreated(Order order) {
        publish(OrderEventFactory.created(order));
    }

    public void publishPlaced(Order order, String placedBy) {
        publish(OrderEventFactory.placed(order, placedBy));
    }

    public void publishConfirmed(Order order, String confirmedBy, String reason) {
        publish(OrderEventFactory.confirmed(order, confirmedBy, reason));
    }

    public void publishShipped(Order order, String shippedBy, String reason) {
        publish(OrderEventFactory.shipped(order, shippedBy, reason));
    }

    public void publishDelivered(Order order, String deliveredBy, String reason) {
        publish(OrderEventFactory.delivered(order, deliveredBy, reason));
    }

    public void publishCancelled(Order order, String cancelledBy, String reason) {
        publish(OrderEventFactory.cancelled(order, cancelledBy, reason));
    }
}

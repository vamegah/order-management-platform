package com.example.omp.inventory.events;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.shared.events.BaseEvent;
import com.example.omp.shared.events.KafkaTopics;
import com.example.omp.shared.events.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryEventPublisher {

    private final OutboxWriter outbox;

    public InventoryEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    private void publish(BaseEvent event) {
        outbox.write(event, KafkaTopics.INVENTORY_EVENTS_RESERVATION);
    }

    public void publishReservationCreated(Reservation r) {
        publish(InventoryEventFactory.created(r));
    }

    public void publishReservationCommitted(Reservation r, String reason) {
        publish(InventoryEventFactory.committed(r, reason));
    }

    public void publishReservationReleased(Reservation r, String reason) {
        publish(InventoryEventFactory.released(r, reason));
    }

    public void publishReservationExpired(Reservation r, String reason) {
        publish(InventoryEventFactory.expired(r, reason));
    }
}

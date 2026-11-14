package com.example.omp.inventory.events;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.grpc.handler.StockUpdateStreamHandler;
import com.example.omp.inventory.repository.StockLevelRepository;
import com.example.omp.proto.inventory.StockUpdateReason;
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
    private final StockUpdateStreamHandler streams;
    private final StockLevelRepository stock;

    public InventoryEventPublisher(
            OutboxWriter outbox, StockUpdateStreamHandler streams, StockLevelRepository stock) {
        this.outbox = outbox;
        this.streams = streams;
        this.stock = stock;
    }

    private void notifyStock(Reservation reservation, StockUpdateReason reason) {
        stock.findBySkuAndWarehouseId(reservation.getSku(), reservation.getWarehouseId())
                .ifPresent(level -> streams.publishUpdate(level, reason));
    }

    private void publish(BaseEvent event) {
        outbox.write(event, KafkaTopics.INVENTORY_EVENTS_RESERVATION);
    }

    public void publishReservationCreated(Reservation r) {
        publish(InventoryEventFactory.created(r));
        notifyStock(r, StockUpdateReason.STOCK_UPDATE_REASON_RESERVED);
    }

    public void publishReservationCommitted(Reservation r, String reason) {
        publish(InventoryEventFactory.committed(r, reason));
        notifyStock(r, StockUpdateReason.STOCK_UPDATE_REASON_COMMITTED);
    }

    public void publishReservationReleased(Reservation r, String reason) {
        publish(InventoryEventFactory.released(r, reason));
        notifyStock(
                r,
                r.getStatus() == com.example.omp.inventory.domain.ReservationStatus.RESTOCKED
                        ? StockUpdateReason.STOCK_UPDATE_REASON_RESTOCKED
                        : StockUpdateReason.STOCK_UPDATE_REASON_RELEASED);
    }

    public void publishReservationExpired(Reservation r, String reason) {
        publish(InventoryEventFactory.expired(r, reason));
        notifyStock(r, StockUpdateReason.STOCK_UPDATE_REASON_RELEASED);
    }
}

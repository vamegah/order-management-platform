package com.example.omp.inventory.grpc.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.repository.StockLevelRepository;
import com.example.omp.proto.inventory.StockUpdate;
import com.example.omp.proto.inventory.StockUpdateReason;
import com.example.omp.proto.inventory.StreamStockUpdatesRequest;
import com.example.omp.shared.events.outbox.OutboxWriter;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class InventoryStreamPublicationTest {
    private final StockLevelRepository stock = mock(StockLevelRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final StockUpdateStreamHandler streams = new StockUpdateStreamHandler(stock);
    private final InventoryEventPublisher publisher =
            new InventoryEventPublisher(outbox, streams, stock);
    private final StockLevel level = new StockLevel("SKU", "WH-1", 10);

    @Test
    void publishesImmutableReservationCommitRestockCancellationAndExpirySnapshots() {
        when(stock.findBySkuAndWarehouseId("SKU", "WH-1")).thenReturn(Optional.of(level));
        var observer = observer();
        streams.streamUpdates(StreamStockUpdatesRequest.getDefaultInstance(), observer);
        var committed = reservation(2);
        level.reserve(2);
        publisher.publishReservationCreated(committed);
        level.commit(2);
        committed.commit();
        publisher.publishReservationCommitted(committed, "shipped");
        level.restock(2);
        committed.compensateCommitted();
        publisher.publishReservationReleased(committed, "compensated");
        var cancelled = reservation(1);
        level.reserve(1);
        publisher.publishReservationCreated(cancelled);
        level.release(1);
        cancelled.release();
        publisher.publishReservationReleased(cancelled, "cancelled");
        var expired = reservation(1);
        level.reserve(1);
        publisher.publishReservationCreated(expired);
        level.release(1);
        expired.expire();
        publisher.publishReservationExpired(expired, "expired");
        var updates = ArgumentCaptor.forClass(StockUpdate.class);
        verify(observer, times(7)).onNext(updates.capture());
        assertThat(updates.getAllValues())
                .extracting(StockUpdate::getReason)
                .containsExactly(
                        StockUpdateReason.STOCK_UPDATE_REASON_RESERVED,
                        StockUpdateReason.STOCK_UPDATE_REASON_COMMITTED,
                        StockUpdateReason.STOCK_UPDATE_REASON_RESTOCKED,
                        StockUpdateReason.STOCK_UPDATE_REASON_RESERVED,
                        StockUpdateReason.STOCK_UPDATE_REASON_RELEASED,
                        StockUpdateReason.STOCK_UPDATE_REASON_RESERVED,
                        StockUpdateReason.STOCK_UPDATE_REASON_RELEASED);
        assertThat(updates.getAllValues().getFirst().getQuantityReserved()).isEqualTo(2);
        assertThat(updates.getAllValues().getLast().getQuantityReserved()).isZero();
        assertThat(updates.getAllValues().getLast().getQuantityAvailable()).isEqualTo(10);
        verify(outbox, times(7)).write(any(), anyString());
    }

    @Test
    void dropsSlowConsumerUpdatesAndRemovesCancelledSubscriptions() {
        var observer = observer();
        when(observer.isReady()).thenReturn(false);
        streams.streamUpdates(StreamStockUpdatesRequest.getDefaultInstance(), observer);
        streams.publishUpdate(level, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
        verify(observer, never()).onNext(any());
        when(observer.isReady()).thenReturn(true);
        streams.publishUpdate(level, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
        verify(observer).onNext(any());
        var cancelled = ArgumentCaptor.forClass(Runnable.class);
        verify(observer).setOnCancelHandler(cancelled.capture());
        cancelled.getValue().run();
        streams.publishUpdate(level, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
        verify(observer, times(1)).onNext(any());
    }

    @Test
    void filtersUnrelatedStockAndCleansUpDisconnectedObservers() {
        var unrelated = observer();
        streams.streamUpdates(
                StreamStockUpdatesRequest.newBuilder().setWarehouseId("WH-2").build(), unrelated);
        var otherSku = observer();
        streams.streamUpdates(
                StreamStockUpdatesRequest.newBuilder()
                        .addSkus("OTHER")
                        .setWarehouseId("WH-1")
                        .build(),
                otherSku);
        var disconnected = observer();
        doThrow(new IllegalStateException("disconnected")).when(disconnected).onNext(any());
        streams.streamUpdates(StreamStockUpdatesRequest.getDefaultInstance(), disconnected);
        streams.publishUpdate(level, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
        streams.publishUpdate(level, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
        verify(unrelated, never()).onNext(any());
        verify(otherSku, never()).onNext(any());
        verify(disconnected, times(1)).onNext(any());
        var invalid = observer();
        streams.streamUpdates(StreamStockUpdatesRequest.newBuilder().addSkus("").build(), invalid);
        var failure = ArgumentCaptor.forClass(Throwable.class);
        verify(invalid).onError(failure.capture());
        assertThat(Status.fromThrowable(failure.getValue()).getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private Reservation reservation(int quantity) {
        var reservation = new Reservation("SKU", "WH-1", quantity, UUID.randomUUID());
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        return reservation;
    }

    @SuppressWarnings("unchecked")
    private ServerCallStreamObserver<StockUpdate> observer() {
        var observer = (ServerCallStreamObserver<StockUpdate>) mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(true);
        return observer;
    }
}

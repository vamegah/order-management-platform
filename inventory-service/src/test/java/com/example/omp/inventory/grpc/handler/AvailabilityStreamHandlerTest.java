package com.example.omp.inventory.grpc.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.proto.inventory.*;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AvailabilityStreamHandlerTest {
    @SuppressWarnings("unchecked")
    private final ServerCallStreamObserver<CheckAvailabilityResponse> response =
            mock(ServerCallStreamObserver.class);

    private final StockQueryHandler stock = mock(StockQueryHandler.class);
    private final AvailabilityStreamHandler handler = new AvailabilityStreamHandler(stock);
    private final CheckAvailabilityRequest query =
            CheckAvailabilityRequest.newBuilder()
                    .setSku("SKU")
                    .setWarehouseId("WH")
                    .setRequestedQuantity(1)
                    .build();

    @Test
    void downstreamPressureBoundsTheWindowAndHalfCloseDrainsThePendingReply() {
        var input = handler.open(response);
        var reply = CheckAvailabilityResponse.newBuilder().setAvailable(true).build();
        when(stock.checkAvailability(query)).thenReturn(reply);
        input.onNext(query);
        input.onCompleted();
        verify(response, never()).onNext(any());
        verify(response, never()).onCompleted();
        verify(response, times(1)).request(1);
        var ready = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setOnReadyHandler(ready.capture());
        when(response.isReady()).thenReturn(true);
        ready.getValue().run();
        verify(response).onNext(reply);
        verify(response).onCompleted();
        ready.getValue().run();
        input.onNext(query);
        verify(stock, times(1)).checkAvailability(query);
    }

    @Test
    void replenishesOnlyAfterDeliveryAndCancellationDiscardsPendingWork() {
        when(response.isReady()).thenReturn(true);
        when(stock.checkAvailability(query))
                .thenReturn(CheckAvailabilityResponse.getDefaultInstance());
        var input = handler.open(response);
        input.onNext(query);
        verify(response, times(2)).request(1);
        var cancel = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setOnCancelHandler(cancel.capture());
        cancel.getValue().run();
        input.onNext(query);
        input.onError(new RuntimeException());
        input.onCompleted();
        verify(stock, times(1)).checkAvailability(query);
        verify(response, never()).onCompleted();
    }

    @Test
    void validationAndDomainErrorsTerminateWithStableCodes() {
        var input = handler.open(response);
        input.onNext(CheckAvailabilityRequest.getDefaultInstance());
        var failure = ArgumentCaptor.forClass(Throwable.class);
        verify(response).onError(failure.capture());
        assertThat(Status.fromThrowable(failure.getValue()).getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        input.onNext(query);
        verifyNoInteractions(stock);
        reset(response);
        when(stock.checkAvailability(query))
                .thenThrow(
                        new com.example.omp.inventory.error.InventoryNotFoundException("missing"));
        handler.open(response).onNext(query);
        verify(response).onError(failure.capture());
        assertThat(Status.fromThrowable(failure.getValue()).getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }
}

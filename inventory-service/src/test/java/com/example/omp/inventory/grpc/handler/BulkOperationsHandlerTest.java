package com.example.omp.inventory.grpc.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.error.InsufficientStockException;
import com.example.omp.inventory.error.InventoryNotFoundException;
import com.example.omp.proto.inventory.*;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.internal.testing.StreamRecorder;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BulkOperationsHandlerTest {
    private final ReservationHandler reservations = mock(ReservationHandler.class);
    private final BulkOperationsHandler handler = new BulkOperationsHandler(reservations);
    private final String order = UUID.randomUUID().toString();

    @Test
    void delegatesReservationAndPreservesPartialBusinessFailures() {
        var accepted = Reservation.newBuilder().setId(UUID.randomUUID().toString()).build();
        when(reservations.reserve(any()))
                .thenAnswer(
                        call -> {
                            ReserveStockRequest request = call.getArgument(0);
                            return switch (request.getSku()) {
                                case "GOOD" -> accepted;
                                case "EMPTY" ->
                                        throw new InsufficientStockException("Insufficient stock");
                                case "MISSING" ->
                                        throw new InventoryNotFoundException("Unknown SKU");
                                case "CHANGED" ->
                                        throw new IllegalArgumentException(
                                                "Retry changed quantity");
                                default ->
                                        throw new IllegalStateException(
                                                "Reservation no longer active");
                            };
                        });
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = handler.bulkReserve(output);
        for (String sku : new String[] {"GOOD", "EMPTY", "MISSING", "CHANGED", "TERMINAL"}) {
            input.onNext(item(sku, order));
        }
        verifyNoInteractions(reservations);
        input.onCompleted();
        input.onCompleted();
        assertThat(output.getError()).isNull();
        assertThat(output.getValues())
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.getReservationsList()).containsExactly(accepted);
                            assertThat(result.getFailuresList())
                                    .extracting(FailedItem::getSku)
                                    .containsExactly("EMPTY", "MISSING", "CHANGED", "TERMINAL");
                        });
        verify(reservations)
                .reserve(
                        ReserveStockRequest.newBuilder()
                                .setSku("GOOD")
                                .setWarehouseId("WH-1")
                                .setQuantity(2)
                                .setOrderId(order)
                                .build());
        verify(reservations, times(5)).reserve(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "1-1-1-1-1", "", "00000000-0000-0000-0000-000000000001"})
    void rejectsInvalidOrMixedOrdersBeforeReservingAnything(String otherOrder) {
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = handler.bulkReserve(output);
        input.onNext(item("FIRST", order));
        input.onNext(item("SECOND", otherOrder));
        input.onCompleted();
        assertThat(Status.fromThrowable(output.getError()).getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(output.getValues()).isEmpty();
        verifyNoInteractions(reservations);
    }

    @Test
    void rejectsOversizedStreamsBeforeAnyWritesAndIgnoresLaterCallbacks() {
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = handler.bulkReserve(output);
        for (int i = 0; i < 102; i++) input.onNext(item("SKU-" + i, order));
        input.onCompleted();
        assertThat(Status.fromThrowable(output.getError()).getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        verifyNoInteractions(reservations);
    }

    @Test
    void acceptsTheExactBoundAndAnEmptyStream() {
        when(reservations.reserve(any())).thenReturn(Reservation.getDefaultInstance());
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = handler.bulkReserve(output);
        for (int i = 0; i < 100; i++) input.onNext(item("SKU-" + i, order));
        input.onCompleted();
        assertThat(output.getValues().getFirst().getReservationsCount()).isEqualTo(100);
        var empty = StreamRecorder.<BulkReserveResponse>create();
        handler.bulkReserve(empty).onCompleted();
        assertThat(empty.getValues()).containsExactly(BulkReserveResponse.getDefaultInstance());
    }

    @Test
    void cancellationDiscardsBufferedItems() {
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = handler.bulkReserve(output);
        input.onNext(item("SKU", order));
        input.onError(Status.CANCELLED.asRuntimeException());
        input.onNext(item("LATE", order));
        input.onCompleted();
        verifyNoInteractions(reservations);
        assertThat(output.getValues()).isEmpty();
    }

    @Test
    void cancelledContextStopsProcessingBeforeTheFirstWrite() {
        try (var context = Context.current().withCancellation()) {
            var output = StreamRecorder.<BulkReserveResponse>create();
            var input = handler.bulkReserve(output);
            input.onNext(item("SKU", order));
            context.cancel(null);
            context.run(input::onCompleted);
            assertThat(Status.fromThrowable(output.getError()).getCode())
                    .isEqualTo(Status.Code.CANCELLED);
            verifyNoInteractions(reservations);
        }
    }

    @Test
    void infrastructureFailuresAreRedactedAndDoNotReportSuccess() {
        when(reservations.reserve(any())).thenThrow(new RuntimeException("private database URI"));
        var output = StreamRecorder.<BulkReserveResponse>create();
        var input = handler.bulkReserve(output);
        input.onNext(item("SKU", order));
        input.onCompleted();
        Status status = Status.fromThrowable(output.getError());
        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).doesNotContain("private");
        assertThat(output.getValues()).isEmpty();
    }

    private BulkReserveItem item(String sku, String orderId) {
        return BulkReserveItem.newBuilder()
                .setSku(sku)
                .setWarehouseId("WH-1")
                .setQuantity(2)
                .setOrderId(orderId)
                .build();
    }
}

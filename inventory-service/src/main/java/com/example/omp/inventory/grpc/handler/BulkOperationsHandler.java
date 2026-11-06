package com.example.omp.inventory.grpc.handler;

import com.example.omp.inventory.error.InsufficientStockException;
import com.example.omp.inventory.error.InventoryNotFoundException;
import com.example.omp.proto.inventory.BulkReserveItem;
import com.example.omp.proto.inventory.BulkReserveResponse;
import com.example.omp.proto.inventory.FailedItem;
import com.example.omp.proto.inventory.ReserveStockRequest;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Bounded client streaming with the same transaction and retry rules as unary reservations. */
@Component
public class BulkOperationsHandler {
    static final int MAX_ITEMS = 100;
    private final ReservationHandler reservations;

    public BulkOperationsHandler(ReservationHandler reservations) {
        this.reservations = reservations;
    }

    public StreamObserver<BulkReserveItem> bulkReserve(
            StreamObserver<BulkReserveResponse> response) {
        List<BulkReserveItem> items = new ArrayList<>();
        AtomicBoolean terminated = new AtomicBoolean();
        return new StreamObserver<>() {
            @Override
            public void onNext(BulkReserveItem item) {
                if (terminated.get()) return;
                if (items.size() == MAX_ITEMS) {
                    terminated.set(true);
                    items.clear();
                    response.onError(
                            Status.RESOURCE_EXHAUSTED
                                    .withDescription("Bulk reserve accepts at most 100 items")
                                    .asRuntimeException());
                    return;
                }
                items.add(item);
            }

            @Override
            public void onError(Throwable failure) {
                terminated.set(true);
                items.clear();
            }

            @Override
            public void onCompleted() {
                if (!terminated.compareAndSet(false, true)) return;
                try {
                    validateOrder(items);
                    var result = BulkReserveResponse.newBuilder();
                    for (BulkReserveItem item : items) {
                        if (Context.current().isCancelled()) {
                            throw Status.CANCELLED.asRuntimeException();
                        }
                        try {
                            // Cross the Spring proxy so stock, hold and outbox commit together.
                            result.addReservations(
                                    reservations.reserve(
                                            ReserveStockRequest.newBuilder()
                                                    .setSku(item.getSku())
                                                    .setWarehouseId(item.getWarehouseId())
                                                    .setQuantity(item.getQuantity())
                                                    .setOrderId(item.getOrderId())
                                                    .build()));
                        } catch (InsufficientStockException
                                | InventoryNotFoundException
                                | IllegalArgumentException
                                | IllegalStateException failure) {
                            result.addFailures(
                                    FailedItem.newBuilder()
                                            .setSku(item.getSku())
                                            .setRequestedQuantity(item.getQuantity())
                                            .setReason(
                                                    failure.getMessage() == null
                                                            ? "Reservation rejected"
                                                            : failure.getMessage())
                                            .build());
                        }
                    }
                    response.onNext(result.build());
                    response.onCompleted();
                } catch (Exception failure) {
                    Status status = Status.fromThrowable(failure);
                    if (status.getCode() == Status.Code.UNKNOWN) {
                        status =
                                Status.INTERNAL.withDescription(
                                        "Bulk reservation failed; retry safely");
                    }
                    response.onError(status.withCause(failure).asRuntimeException());
                } finally {
                    items.clear();
                }
            }
        };
    }

    private static void validateOrder(List<BulkReserveItem> items) {
        UUID order = null;
        for (BulkReserveItem item : items) {
            try {
                UUID current = UUID.fromString(item.getOrderId());
                if (!current.toString().equalsIgnoreCase(item.getOrderId())
                        || (order != null && !order.equals(current))) {
                    throw new IllegalArgumentException();
                }
                order = current;
            } catch (IllegalArgumentException invalid) {
                throw Status.INVALID_ARGUMENT
                        .withDescription("Bulk items must reference one canonical order UUID")
                        .asRuntimeException();
            }
        }
    }
}

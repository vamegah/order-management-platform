package com.example.omp.inventory.grpc.handler;

import com.example.omp.inventory.grpc.advice.GrpcExceptionAdvice;
import com.example.omp.proto.inventory.CheckAvailabilityRequest;
import com.example.omp.proto.inventory.CheckAvailabilityResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/** A one-message inbound window and one pending reply bound memory under downstream pressure. */
public final class AvailabilityStreamHandler {
    private final StockQueryHandler stock;

    public AvailabilityStreamHandler(StockQueryHandler stock) {
        this.stock = stock;
    }

    public StreamObserver<CheckAvailabilityRequest> open(
            StreamObserver<CheckAvailabilityResponse> observer) {
        var response = (ServerCallStreamObserver<CheckAvailabilityResponse>) observer;
        var session = new Session(response);
        response.disableAutoRequest();
        response.setOnCancelHandler(session::cancel);
        response.setOnReadyHandler(session::drain);
        response.request(1);
        return session;
    }

    private final class Session implements StreamObserver<CheckAvailabilityRequest> {
        private final ServerCallStreamObserver<CheckAvailabilityResponse> response;
        private CheckAvailabilityResponse pending;
        private boolean ended;
        private boolean halfClosed;

        Session(ServerCallStreamObserver<CheckAvailabilityResponse> response) {
            this.response = response;
        }

        @Override
        public synchronized void onNext(CheckAvailabilityRequest request) {
            if (ended || response.isCancelled()) return;
            try {
                if (request.getSku().isBlank()
                        || request.getSku().length() > 100
                        || request.getWarehouseId().isBlank()
                        || request.getWarehouseId().length() > 100
                        || request.getRequestedQuantity() < 1)
                    throw new IllegalArgumentException("Invalid availability query");
                pending = stock.checkAvailability(request);
                drain();
            } catch (Exception failure) {
                ended = true;
                pending = null;
                response.onError(GrpcExceptionAdvice.toStatus(failure).asRuntimeException());
            }
        }

        synchronized void drain() {
            if (ended || response.isCancelled()) return;
            if (pending != null && response.isReady()) {
                var value = pending;
                pending = null;
                response.onNext(value);
                if (!halfClosed) response.request(1);
            }
            if (halfClosed && pending == null) {
                ended = true;
                response.onCompleted();
            }
        }

        synchronized void cancel() {
            ended = true;
            pending = null;
        }

        @Override
        public synchronized void onError(Throwable failure) {
            cancel();
        }

        @Override
        public synchronized void onCompleted() {
            halfClosed = true;
            drain();
        }
    }
}

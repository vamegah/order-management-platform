package com.example.omp.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.concurrent.*;

/** Explicit inbound demand, observable cancellation, and bounded result buffering. */
public final class GrpcProbe<Q, S> implements ClientResponseObserver<Q, S> {
    private final BlockingQueue<S> values = new ArrayBlockingQueue<>(1024);
    private final CompletableFuture<Status> terminal = new CompletableFuture<>();
    private final boolean manual;
    private volatile ClientCallStreamObserver<Q> request;

    public GrpcProbe() {
        this(false);
    }

    public GrpcProbe(boolean manual) {
        this.manual = manual;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<Q> request) {
        this.request = request;
        if (manual) request.disableAutoRequestWithInitial(1);
    }

    @Override
    public void onNext(S value) {
        if (!values.offer(value)) {
            request.cancel("Acceptance consumer buffer exceeded", null);
            terminal.complete(Status.RESOURCE_EXHAUSTED);
        }
    }

    @Override
    public void onError(Throwable error) {
        terminal.complete(Status.fromThrowable(error));
    }

    @Override
    public void onCompleted() {
        terminal.complete(Status.OK);
    }

    public S next() throws Exception {
        S value = values.poll(10, TimeUnit.SECONDS);
        assertThat(value)
                .as("Expected gRPC response; terminal=%s", terminal.getNow(null))
                .isNotNull();
        return value;
    }

    public Status finish() throws Exception {
        return terminal.get(15, TimeUnit.SECONDS);
    }

    public void request(int count) {
        request.request(count);
    }

    public void cancel() {
        request.cancel("Acceptance client cancelled", null);
    }

    public boolean done() {
        return terminal.isDone();
    }

    public int buffered() {
        return values.size();
    }
}

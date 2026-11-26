package com.example.omp.shared.grpc;

import io.grpc.*;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.atomic.AtomicBoolean;

/** W3C-only context with callback scopes for unary and all streaming lifecycles. */
public final class GrpcTraceInterceptors implements ClientInterceptor, ServerInterceptor {
    private static final Metadata.Key<String> PARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private final Tracer tracer;
    private final Propagator propagator;

    public GrpcTraceInterceptors(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    private void finish(Span span, AtomicBoolean ended, Status status) {
        if (ended.compareAndSet(false, true)) {
            span.tag("rpc.grpc.status_code", Integer.toString(status.getCode().value()));
            if (!status.isOk())
                span.error(new IllegalStateException("RPC_" + status.getCode().name()));
            span.end();
        }
    }

    private void scoped(Span span, Runnable action) {
        try (var scope = tracer.withSpan(span)) {
            action.run();
        }
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel channel) {
        Span span =
                tracer.spanBuilder()
                        .name("grpc.client " + method.getFullMethodName())
                        .kind(Span.Kind.CLIENT)
                        .start();
        var ended = new AtomicBoolean();
        ClientCall<ReqT, RespT> delegate;
        try (var scope = tracer.withSpan(span)) {
            delegate = channel.newCall(method, options);
        } catch (RuntimeException failure) {
            finish(span, ended, Status.INTERNAL);
            throw failure;
        }
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                propagator.inject(
                        span.context(),
                        headers,
                        (carrier, key, value) -> {
                            if (key.equals("traceparent")) {
                                carrier.discardAll(PARENT);
                                carrier.put(PARENT, value);
                            }
                        });
                var callbacks =
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(
                                listener) {
                            @Override
                            public void onHeaders(Metadata headers) {
                                scoped(span, () -> super.onHeaders(headers));
                            }

                            @Override
                            public void onMessage(RespT response) {
                                scoped(span, () -> super.onMessage(response));
                            }

                            @Override
                            public void onReady() {
                                scoped(span, super::onReady);
                            }

                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                try {
                                    scoped(span, () -> super.onClose(status, trailers));
                                } finally {
                                    finish(span, ended, status);
                                }
                            }
                        };
                try {
                    scoped(span, () -> super.start(callbacks, headers));
                } catch (RuntimeException failure) {
                    finish(span, ended, Status.INTERNAL);
                    throw failure;
                }
            }

            @Override
            public void sendMessage(ReqT value) {
                scoped(span, () -> super.sendMessage(value));
            }

            @Override
            public void halfClose() {
                scoped(span, super::halfClose);
            }

            @Override
            public void cancel(String message, Throwable cause) {
                try {
                    super.cancel(message, cause);
                } finally {
                    finish(span, ended, Status.CANCELLED);
                }
            }
        };
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Span span =
                propagator
                        .extract(
                                headers,
                                (carrier, key) ->
                                        key.equals("traceparent") ? carrier.get(PARENT) : null)
                        .name("grpc.server " + call.getMethodDescriptor().getFullMethodName())
                        .kind(Span.Kind.SERVER)
                        .start();
        var ended = new AtomicBoolean();
        var traced =
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        // Record status before close can re-enter completion callbacks.
                        finish(span, ended, status);
                        scoped(span, () -> super.close(status, trailers));
                    }

                    @Override
                    public void sendMessage(RespT message) {
                        scoped(span, () -> super.sendMessage(message));
                    }
                };
        ServerCall.Listener<ReqT> listener;
        try (var scope = tracer.withSpan(span)) {
            listener = next.startCall(traced, headers);
        } catch (RuntimeException failure) {
            finish(span, ended, Status.INTERNAL);
            throw failure;
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onMessage(ReqT message) {
                scoped(span, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                scoped(span, super::onHalfClose);
            }

            @Override
            public void onReady() {
                scoped(span, super::onReady);
            }

            @Override
            public void onCancel() {
                try {
                    scoped(span, super::onCancel);
                } finally {
                    finish(span, ended, Status.CANCELLED);
                }
            }

            @Override
            public void onComplete() {
                try {
                    scoped(span, super::onComplete);
                } finally {
                    finish(span, ended, Status.OK);
                }
            }
        };
    }
}

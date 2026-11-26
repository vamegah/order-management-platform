package com.example.omp.shared.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.*;
import io.micrometer.tracing.*;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Protocol lifecycle boundaries must retain callback context and finish once, including rejection.
 */
class GrpcTraceLifecycleTest {
    final Tracer tracer = mock(Tracer.class);
    final Propagator propagator = mock(Propagator.class);
    final Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
    final Span span = mock(Span.class);
    final AtomicInteger scopes = new AtomicInteger();
    final GrpcTraceInterceptors tracing;

    GrpcTraceLifecycleTest() {
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(tracer.withSpan(span))
                .thenAnswer(
                        i -> {
                            scopes.incrementAndGet();
                            return (Tracer.SpanInScope) () -> scopes.decrementAndGet();
                        });
        tracing = new GrpcTraceInterceptors(tracer, propagator);
    }

    @Test
    void autoConfigurationRegistersBothGlobalInterceptorsOnlyWhenTracingIsPresent() {
        var runner =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(GrpcTracingAutoConfiguration.class));
        runner.run(c -> assertThat(c).doesNotHaveBean(GrpcTraceInterceptors.class));
        runner.withBean(Tracer.class, () -> tracer)
                .withBean(Propagator.class, () -> propagator)
                .run(
                        c -> {
                            assertThat(c.getBeansOfType(GrpcTraceInterceptors.class))
                                    .containsOnlyKeys("ompClientTracing", "ompServerTracing");
                            assertThat(
                                            c.findAnnotationOnBean(
                                                    "ompClientTracing",
                                                    net.devh.boot.grpc.client.interceptor
                                                            .GrpcGlobalClientInterceptor.class))
                                    .isNotNull();
                            assertThat(
                                            c.findAnnotationOnBean(
                                                    "ompServerTracing",
                                                    net.devh.boot.grpc.server.interceptor
                                                            .GrpcGlobalServerInterceptor.class))
                                    .isNotNull();
                        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientCallbacksHaveScopeAndCancellationEndsSpanOnlyOnce() {
        var transport = mock(ClientCall.class);
        var channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn(transport);
        var call =
                tracing.interceptCall(GrpcBootIntegrationTest.ECHO, CallOptions.DEFAULT, channel);
        var application =
                new ClientCall.Listener<String>() {
                    @Override
                    public void onHeaders(Metadata h) {
                        assertThat(scopes).hasValue(1);
                    }

                    @Override
                    public void onMessage(String m) {
                        assertThat(scopes).hasValue(1);
                    }

                    @Override
                    public void onReady() {
                        assertThat(scopes).hasValue(1);
                    }

                    @Override
                    public void onClose(Status s, Metadata h) {
                        assertThat(scopes).hasValue(1);
                    }
                };
        call.start(application, new Metadata());
        var capture = org.mockito.ArgumentCaptor.forClass(ClientCall.Listener.class);
        verify(transport).start(capture.capture(), any());
        var callbacks = capture.getValue();
        callbacks.onHeaders(new Metadata());
        callbacks.onReady();
        callbacks.onMessage("reply");
        call.sendMessage("request");
        call.halfClose();
        call.cancel("private reason", new IllegalStateException("secret"));
        callbacks.onClose(Status.CANCELLED, new Metadata());
        verify(span, times(1)).end();
        verify(span).tag("rpc.grpc.status_code", "1");
        verify(span).error(argThat(e -> e.getMessage().equals("RPC_CANCELLED")));
        assertThat(scopes).hasValue(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedChannelAndTransportSetupEndSpanAndPreserveOriginalFailure() {
        var channel = mock(Channel.class);
        var error = new IllegalStateException("private transport details");
        when(channel.newCall(any(), any())).thenThrow(error);
        assertThatThrownBy(
                        () ->
                                tracing.interceptCall(
                                        GrpcBootIntegrationTest.ECHO, CallOptions.DEFAULT, channel))
                .isSameAs(error);
        verify(span).end();
        assertThat(scopes).hasValue(0);
        clearInvocations(span);
        var transport = mock(ClientCall.class);
        doReturn(transport).when(channel).newCall(any(), any());
        doThrow(error).when(transport).start(any(), any());
        var call =
                tracing.interceptCall(GrpcBootIntegrationTest.ECHO, CallOptions.DEFAULT, channel);
        assertThatThrownBy(() -> call.start(new ClientCall.Listener<>() {}, new Metadata()))
                .isSameAs(error);
        verify(span).end();
        verify(span).error(argThat(e -> e.getMessage().equals("RPC_INTERNAL")));
        assertThat(scopes).hasValue(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverReadyAndCancellationCallbacksRestoreScopeAndEndExactlyOnce() {
        var transport = mock(ServerCall.class);
        when(transport.getMethodDescriptor()).thenReturn(GrpcBootIntegrationTest.ECHO);
        var handler = mock(ServerCallHandler.class);
        var application =
                new ServerCall.Listener<String>() {
                    @Override
                    public void onReady() {
                        assertThat(scopes).hasValue(1);
                    }

                    @Override
                    public void onCancel() {
                        assertThat(scopes).hasValue(1);
                    }
                };
        when(handler.startCall(any(), any())).thenReturn(application);
        var listener = tracing.interceptCall(transport, new Metadata(), handler);
        listener.onReady();
        listener.onCancel();
        listener.onComplete();
        verify(span, times(1)).end();
        verify(span).error(argThat(e -> e.getMessage().equals("RPC_CANCELLED")));
        assertThat(scopes).hasValue(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverHandlerRejectionEndsSpanWithoutExposingItsException() {
        var transport = mock(ServerCall.class);
        when(transport.getMethodDescriptor()).thenReturn(GrpcBootIntegrationTest.ECHO);
        var handler = mock(ServerCallHandler.class);
        var error = new IllegalStateException("private handler details");
        when(handler.startCall(any(), any())).thenThrow(error);
        assertThatThrownBy(() -> tracing.interceptCall(transport, new Metadata(), handler))
                .isSameAs(error);
        verify(span).end();
        verify(span).error(argThat(e -> e.getMessage().equals("RPC_INTERNAL")));
        assertThat(scopes).hasValue(0);
    }
}

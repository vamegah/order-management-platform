package com.example.omp.shared.grpc;

import static org.assertj.core.api.Assertions.*;

import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.*;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class GrpcTraceInterceptorsTest {
    @Test
    void realUnaryRpcPreservesParentAndRestoresThreadContextsWithRedactedErrors() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var sdk =
                    OpenTelemetrySdk.builder()
                            .setTracerProvider(provider)
                            .setPropagators(
                                    ContextPropagators.create(
                                            W3CTraceContextPropagator.getInstance()))
                            .build();
            var tracer =
                    new OtelTracer(
                            sdk.getTracer("omp-test"), new OtelCurrentTraceContext(), event -> {});
            var propagator = new OtelPropagator(sdk.getPropagators(), sdk.getTracer("omp-test"));
            var interceptor = new GrpcTraceInterceptors(tracer, propagator);
            var worker = Executors.newSingleThreadExecutor();
            var name = InProcessServerBuilder.generateName();
            var method = GrpcBootIntegrationTest.ECHO;
            var service =
                    ServerServiceDefinition.builder("test.Echo")
                            .addMethod(
                                    method,
                                    ServerCalls.asyncUnaryCall(
                                            (String request, StreamObserver<String> response) -> {
                                                assertThat(tracer.currentSpan()).isNotNull();
                                                if (request.equals("fail"))
                                                    response.onError(
                                                            Status.PERMISSION_DENIED
                                                                    .withDescription(
                                                                            "private credentials")
                                                                    .asRuntimeException());
                                                else {
                                                    response.onNext(
                                                            tracer.currentSpan()
                                                                    .context()
                                                                    .traceId());
                                                    response.onCompleted();
                                                }
                                            }))
                            .build();
            var server =
                    InProcessServerBuilder.forName(name)
                            .executor(worker)
                            .intercept(interceptor)
                            .addService(service)
                            .build()
                            .start();
            var channel = InProcessChannelBuilder.forName(name).intercept(interceptor).build();
            var root = tracer.nextSpan().name("checkout").start();
            try (var scope = tracer.withSpan(root)) {
                assertThat(
                                ClientCalls.blockingUnaryCall(
                                        channel, method, CallOptions.DEFAULT, "success"))
                        .isEqualTo(root.context().traceId());
                assertThat(tracer.currentSpan().context().spanId())
                        .isEqualTo(root.context().spanId());
                assertThatThrownBy(
                                () ->
                                        ClientCalls.blockingUnaryCall(
                                                channel, method, CallOptions.DEFAULT, "fail"))
                        .isInstanceOf(StatusRuntimeException.class);
            } finally {
                root.end();
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                worker.shutdownNow();
            }
            assertThat(tracer.currentSpan()).isNull();
            var spans = exporter.getFinishedSpanItems();
            assertThat(spans)
                    .hasSize(5)
                    .allMatch(s -> s.getTraceId().equals(root.context().traceId()));
            var client =
                    spans.stream()
                            .filter(
                                    s ->
                                            s.getName().startsWith("grpc.client")
                                                    && s.getStatus().getStatusCode()
                                                            != StatusCode.ERROR)
                            .findFirst()
                            .orElseThrow();
            assertThat(
                            spans.stream()
                                    .filter(
                                            s ->
                                                    s.getName().startsWith("grpc.server")
                                                            && s.getStatus().getStatusCode()
                                                                    != StatusCode.ERROR)
                                    .findFirst()
                                    .orElseThrow()
                                    .getParentSpanId())
                    .isEqualTo(client.getSpanId());
            assertThat(
                            spans.stream()
                                    .filter(s -> s.getStatus().getStatusCode() == StatusCode.ERROR))
                    .hasSize(2);
            assertThat(spans.toString()).doesNotContain("private credentials");
        }
    }
}

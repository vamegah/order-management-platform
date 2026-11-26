package com.example.omp.shared.events;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.shared.events.tracing.EventTracing;
import io.micrometer.tracing.*;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventTracingTest {
    private static final String PARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    @Test
    void acceptsOnlyBoundedNonzeroW3cIdentifiersAndNeverBaggage() {
        var tracing = new EventTracing();
        assertThat(tracing.parent("{\"traceparent\":\"" + PARENT + "\",\"baggage\":\"secret\"}"))
                .isEqualTo(PARENT);
        for (String invalid :
                java.util.List.of(
                        "bad",
                        "00-" + "0".repeat(32) + "-0123456789abcdef-01",
                        "00-0123456789abcdef0123456789abcdef-" + "0".repeat(16) + "-01",
                        PARENT + "secret")) assertThat(EventTracing.validated(invalid)).isNull();
        // W3C flags include sampled and random bits; unknown bits must not invalidate an ID.
        for (String flags : java.util.List.of("00", "01", "02", "03", "ff")) {
            String parent = PARENT.substring(0, 53) + flags;
            assertThat(EventTracing.validated(parent)).isEqualTo(parent);
            assertThat(tracing.parent("{\"traceparent\":\"" + parent + "\"}")).isEqualTo(parent);
        }
        assertThat(tracing.parent("malformed")).isNull();
        assertThat(tracing.parent("x".repeat(262145))).isNull();
        assertThat(tracing.parent(null)).isNull();
        assertThat(tracing.attach("{\"data\":1}", PARENT))
                .contains("traceparent")
                .doesNotContain("baggage");
        assertThat(tracing.attach("{}", "invalid")).isEqualTo("{}");
        assertThat(tracing.attach("malformed", PARENT)).isEqualTo("malformed");
        assertThat(tracing.attach("[]", PARENT)).isEqualTo("[]");
    }

    @Test
    void absentTelemetryNeverChangesWorkOrExceptions() {
        var tracing = new EventTracing();
        var calls = new AtomicInteger();
        tracing.run(PARENT, "event.ingest", calls::incrementAndGet);
        assertThat(calls).hasValue(1);
        assertThat(tracing.current()).isNull();
        assertThatThrownBy(
                        () ->
                                tracing.run(
                                        PARENT,
                                        "event.ingest",
                                        () -> {
                                            throw new IllegalStateException("business");
                                        }))
                .hasMessage("business");
    }

    @Test
    void durableParentAndClassifiedFailuresKeepOnlyW3cContext() {
        var tracer = mock(Tracer.class);
        var propagation = mock(Propagator.class);
        var builder = mock(Span.Builder.class, RETURNS_SELF);
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(context.spanId()).thenReturn("0123456789abcdef");
        when(context.sampled()).thenReturn(true, false);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
        when(builder.start()).thenReturn(span);
        when(propagation.extract(any(), any())).thenReturn(builder);
        when(tracer.withSpan(span)).thenReturn(() -> {});
        var tracing = new EventTracing();
        tracing.failed();
        tracing.configure(tracer, propagation);
        assertThat(tracing.current()).isEqualTo(PARENT);
        assertThat(tracing.current()).isEqualTo(PARENT.substring(0, 53) + "00");
        tracing.run(PARENT, "durable.retry", tracing::failed);
        verify(span).error(argThat(e -> e.getMessage().equals("PROTOCOL_OPERATION_FAILED")));
        verify(span).end();
        verify(propagation).extract(eq(java.util.Map.of("traceparent", PARENT)), any());
        when(tracer.currentSpan()).thenReturn(null);
        assertThat(tracing.current()).isNull();
        tracing.failed();
    }

    @Test
    void restoresScopeAndEndsSpanOnFailureWithoutCopyingExceptionSecrets() {
        var tracer = mock(Tracer.class);
        var propagation = mock(Propagator.class);
        var builder = mock(Span.Builder.class, RETURNS_SELF);
        var span = mock(Span.class);
        var scope = mock(Tracer.SpanInScope.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        var tracing = new EventTracing();
        tracing.configure(tracer, propagation);
        assertThatThrownBy(
                        () ->
                                tracing.run(
                                        null,
                                        "webhook.deliver",
                                        () -> {
                                            throw new IllegalStateException(
                                                    "private provider secret");
                                        }))
                .hasMessage("private provider secret");
        verify(builder).name("webhook.deliver");
        verify(span).error(argThat(e -> e.getMessage().equals("PROTOCOL_OPERATION_FAILED")));
        verify(scope).close();
        verify(span).end();
    }
}

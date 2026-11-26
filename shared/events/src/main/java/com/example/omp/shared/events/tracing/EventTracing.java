package com.example.omp.shared.events.tracing;

import com.example.omp.shared.events.EventSerde;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;

/**
 * Durable W3C context only: no baggage, authorization, user identity or event data is propagated.
 */
public final class EventTracing {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            EventSerde.buildObjectMapper();
    private static final java.util.regex.Pattern PARENT =
            java.util.regex.Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    private Tracer tracer;
    private Propagator propagator;

    public void configure(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public static String validated(String parent) {
        if (parent == null
                || !PARENT.matcher(parent).matches()
                || parent.substring(3, 35).equals("0".repeat(32))
                || parent.substring(36, 52).equals("0".repeat(16))) return null;
        return parent;
    }

    public String current() {
        var span = tracer == null ? null : tracer.currentSpan();
        if (span == null) return null;
        var context = span.context();
        return validated(
                "00-"
                        + context.traceId()
                        + "-"
                        + context.spanId()
                        + "-"
                        + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00"));
    }

    public String parent(String json) {
        if (json == null || json.length() > 262144) return null;
        try {
            return validated(JSON.readTree(json).path("traceparent").asText(null));
        } catch (Exception invalid) {
            return null;
        }
    }

    public String attach(String json, String parent) {
        if (validated(parent) == null) return json;
        try {
            var tree = JSON.readTree(json);
            if (!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object))
                return json;
            object.put("traceparent", parent);
            return object.toString();
        } catch (Exception invalid) {
            return json;
        }
    }

    /** Mark a classified asynchronous failure without copying payloads or exception messages. */
    public void failed() {
        var span = tracer == null ? null : tracer.currentSpan();
        if (span != null) span.error(new IllegalStateException("PROTOCOL_OPERATION_FAILED"));
    }

    public void run(String parent, String operation, Runnable work) {
        if (tracer == null || propagator == null) {
            work.run();
            return;
        }
        String valid = validated(parent);
        var builder =
                valid == null
                        ? tracer.spanBuilder()
                        : propagator.extract(Map.of("traceparent", valid), Map::get);
        Span span = builder.name(operation).start();
        try (var scope = tracer.withSpan(span)) {
            work.run();
        } catch (RuntimeException failure) {
            span.error(new IllegalStateException("PROTOCOL_OPERATION_FAILED"));
            throw failure;
        } finally {
            span.end();
        }
    }
}

package com.example.omp.adapter.soap;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.transport.HeadersAwareSenderWebServiceConnection;
import org.springframework.ws.transport.context.TransportContextHolder;

/** Propagate W3C over HTTP without adding credentials or changing the frozen SOAP contract. */
public final class SoapTraceInterceptor implements ClientInterceptor {
    private static final String STATE = SoapTraceInterceptor.class.getName();

    private record State(Span span, Tracer.SpanInScope scope) {}

    private final Tracer tracer;

    public SoapTraceInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean handleRequest(MessageContext context) {
        if (tracer == null) return true;
        Span span = tracer.spanBuilder().name("soap.request").kind(Span.Kind.CLIENT).start();
        var scope = tracer.withSpan(span);
        context.setProperty(STATE, new State(span, scope));
        var transport = TransportContextHolder.getTransportContext();
        if (transport != null
                && transport.getConnection()
                        instanceof HeadersAwareSenderWebServiceConnection connection) {
            var value = span.context();
            try {
                connection.addRequestHeader(
                        "traceparent",
                        "00-"
                                + value.traceId()
                                + "-"
                                + value.spanId()
                                + "-"
                                + (Boolean.TRUE.equals(value.sampled()) ? "01" : "00"));
            } catch (java.io.IOException unavailable) {
                // Optional telemetry must not change banking transport or monetary behavior.
                span.error(new IllegalStateException("TRACE_HEADER_UNAVAILABLE"));
            }
        }
        return true;
    }

    @Override
    public boolean handleResponse(MessageContext context) {
        return true;
    }

    @Override
    public boolean handleFault(MessageContext context) {
        if (context.getProperty(STATE) instanceof State state)
            state.span().error(new IllegalStateException("SOAP_FAULT"));
        return true;
    }

    @Override
    public void afterCompletion(MessageContext context, Exception failure) {
        if (context.getProperty(STATE) instanceof State state) {
            try {
                if (failure != null)
                    state.span().error(new IllegalStateException("SOAP_TRANSPORT_FAILURE"));
            } finally {
                state.scope().close();
                state.span().end();
                context.removeProperty(STATE);
            }
        }
    }
}

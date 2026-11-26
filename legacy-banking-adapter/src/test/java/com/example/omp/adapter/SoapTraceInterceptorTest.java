package com.example.omp.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.adapter.soap.SoapTraceInterceptor;
import io.micrometer.tracing.*;
import org.junit.jupiter.api.*;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.*;
import org.springframework.ws.transport.context.*;

class SoapTraceInterceptorTest {
    @AfterEach
    void clear() {
        TransportContextHolder.setTransportContext(null);
    }

    private DefaultMessageContext context() {
        var factory = new SaajSoapMessageFactory();
        factory.afterPropertiesSet();
        return new DefaultMessageContext(factory);
    }

    @Test
    void disabledTelemetryDoesNotChangeSoapExecution() {
        var interceptor = new SoapTraceInterceptor(null);
        var context = context();
        assertThat(interceptor.handleRequest(context)).isTrue();
        assertThat(interceptor.handleResponse(context)).isTrue();
        assertThat(interceptor.handleFault(context)).isTrue();
        interceptor.afterCompletion(context, null);
    }

    @Test
    void propagatesOnlyW3cAndAlwaysRestoresSpanScope() throws Exception {
        var tracer = mock(Tracer.class);
        var builder = mock(Span.Builder.class, RETURNS_SELF);
        var span = mock(Span.class);
        var scope = mock(Tracer.SpanInScope.class);
        var trace = mock(TraceContext.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        when(span.context()).thenReturn(trace);
        when(trace.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(trace.spanId()).thenReturn("0123456789abcdef");
        when(trace.sampled()).thenReturn(true);
        var connection = mock(HeadersAwareSenderWebServiceConnection.class);
        TransportContextHolder.setTransportContext(new DefaultTransportContext(connection));
        var interceptor = new SoapTraceInterceptor(tracer);
        var context = context();
        assertThat(interceptor.handleRequest(context)).isTrue();
        interceptor.handleFault(context);
        interceptor.afterCompletion(context, new java.io.IOException("private backend response"));
        verify(builder).kind(Span.Kind.CLIENT);
        verify(connection)
                .addRequestHeader(
                        "traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        verify(span).error(argThat(e -> e.getMessage().equals("SOAP_FAULT")));
        verify(span).error(argThat(e -> e.getMessage().equals("SOAP_TRANSPORT_FAILURE")));
        verify(scope).close();
        verify(span).end();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void optionalHeaderFailureDoesNotPreventSoapCall() throws Exception {
        var tracer = mock(Tracer.class);
        var builder = mock(Span.Builder.class, RETURNS_SELF);
        var span = mock(Span.class);
        var scope = mock(Tracer.SpanInScope.class);
        var trace = mock(TraceContext.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        when(span.context()).thenReturn(trace);
        when(trace.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(trace.spanId()).thenReturn("0123456789abcdef");
        var connection = mock(HeadersAwareSenderWebServiceConnection.class);
        doThrow(new java.io.IOException())
                .when(connection)
                .addRequestHeader(anyString(), anyString());
        TransportContextHolder.setTransportContext(new DefaultTransportContext(connection));
        var interceptor = new SoapTraceInterceptor(tracer);
        var context = context();
        assertThat(interceptor.handleRequest(context)).isTrue();
        interceptor.afterCompletion(context, null);
        verify(span).error(argThat(e -> e.getMessage().equals("TRACE_HEADER_UNAVAILABLE")));
        verify(scope).close();
        verify(span).end();
    }
}

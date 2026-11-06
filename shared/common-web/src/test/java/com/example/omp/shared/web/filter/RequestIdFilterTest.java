package com.example.omp.shared.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"x\r\nInjected: yes", "request id with spaces", "☃"})
    void replacesUnsafeRequestIds(String header) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, header);
        var response = new MockHttpServletResponse();
        new RequestIdFilter(null).doFilter(request, response, (req, res) -> {});
        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER_NAME))).isNotNull();
    }

    @Test
    void boundsIdsAndRestoresOnlyOwnedMdcEntriesOnUntracedRequests() throws Exception {
        MDC.put(RequestIdFilter.MDC_REQUEST_ID, "outer-request");
        MDC.put(RequestIdFilter.MDC_TRACE_ID, "outer-trace");
        MDC.put(RequestIdFilter.MDC_SPAN_ID, "outer-span");
        MDC.put("application_key", "preserved");
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "x".repeat(129));
        var response = new MockHttpServletResponse();
        new RequestIdFilter(null)
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            assertThat(MDC.get(RequestIdFilter.MDC_TRACE_ID)).isNull();
                            assertThat(MDC.get(RequestIdFilter.MDC_SPAN_ID)).isNull();
                            assertThat(MDC.get("application_key")).isEqualTo("preserved");
                            MDC.put("downstream_key", "retained");
                        });
        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER_NAME))).isNotNull();
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isEqualTo("outer-request");
        assertThat(MDC.get(RequestIdFilter.MDC_TRACE_ID)).isEqualTo("outer-trace");
        assertThat(MDC.get(RequestIdFilter.MDC_SPAN_ID)).isEqualTo("outer-span");
        assertThat(MDC.get("downstream_key")).isEqualTo("retained");
    }

    @Test
    void restoresMdcEvenWhenTraceLookupThrowsBeforeTheChain() {
        MDC.put(RequestIdFilter.MDC_REQUEST_ID, "outer-request");
        when(tracer.currentSpan()).thenThrow(new IllegalStateException("trace lookup failure"));
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse(),
                                        (req, res) -> {
                                            throw new AssertionError("must not run");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isEqualTo("outer-request");
    }

    private final Tracer tracer = mock(Tracer.class);
    private final RequestIdFilter filter = new RequestIdFilter(tracer);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void correlatesResponseAndLogsAndCleansUpAfterFailure() {
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("trace-123");
        when(context.spanId()).thenReturn("span-456");
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "caller-request-789");
        var response = new MockHttpServletResponse();

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (req, res) -> {
                                            assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID))
                                                    .isEqualTo("caller-request-789");
                                            assertThat(MDC.get(RequestIdFilter.MDC_TRACE_ID))
                                                    .isEqualTo("trace-123");
                                            assertThat(MDC.get(RequestIdFilter.MDC_SPAN_ID))
                                                    .isEqualTo("span-456");
                                            throw new ServletException("downstream failure");
                                        }))
                .isInstanceOf(ServletException.class);

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("caller-request-789");
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_SPAN_ID)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void generatesCorrelationIdWhenMissingOrBlank(String header) throws Exception {
        var request = new MockHttpServletRequest();
        if (header != null) request.addHeader(RequestIdFilter.HEADER_NAME, header);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    assertThat(UUID.fromString(MDC.get(RequestIdFilter.MDC_REQUEST_ID)))
                            .isNotNull();
                    assertThat(MDC.get(RequestIdFilter.MDC_TRACE_ID)).isNull();
                });
        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER_NAME))).isNotNull();
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
    }
}

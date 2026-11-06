package com.example.omp.shared.web.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds request correlation to every HTTP request.
 *
 * <p>Logic:
 *
 * <ol>
 *   <li>Use a client {@code X-Request-Id} of at most 128 ASCII letters/digits or {@code ._:-}.
 *   <li>Otherwise, generate a UUID.
 *   <li>Put it in MDC (so log lines carry it) and in the response header.
 *   <li>Capture the trace ID when tracing is available, and restore the caller's MDC afterward.
 * </ol>
 *
 * <p>Runs before Spring Security so that even authenticated-request rejections carry the
 * correlation ID.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final Tracer tracer;

    public RequestIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }

        String previousRequestId = MDC.get(MDC_REQUEST_ID);
        String previousTraceId = MDC.get(MDC_TRACE_ID);
        String previousSpanId = MDC.get(MDC_SPAN_ID);
        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            response.setHeader(HEADER_NAME, requestId);
            Span currentSpan = tracer == null ? null : tracer.currentSpan();
            restore(MDC_TRACE_ID, currentSpan == null ? null : currentSpan.context().traceId());
            restore(MDC_SPAN_ID, currentSpan == null ? null : currentSpan.context().spanId());
            filterChain.doFilter(request, response);
        } finally {
            restore(MDC_REQUEST_ID, previousRequestId);
            restore(MDC_TRACE_ID, previousTraceId);
            restore(MDC_SPAN_ID, previousSpanId);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }
}

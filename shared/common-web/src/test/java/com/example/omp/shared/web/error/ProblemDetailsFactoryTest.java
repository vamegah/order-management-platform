package com.example.omp.shared.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ProblemDetailsFactoryTest {
    private final Tracer tracer = mock(Tracer.class);
    private final ProblemDetailsFactory factory = new ProblemDetailsFactory(tracer);
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v1/orders/123");

    @Test
    void httpStatusAndBodyStatusAlwaysAgree() throws Exception {
        List<ProblemDetails> problems =
                List.of(
                        factory.notFound(HttpStatus.NOT_FOUND, "Not Found", "missing", request),
                        factory.conflict("changed", request),
                        factory.badRequest("invalid", request),
                        factory.unsupportedMediaType("unsupported", request),
                        factory.badGateway("unavailable", request),
                        factory.forbidden("denied", request),
                        factory.internalError("failed", request));

        assertThat(problems)
                .extracting(ProblemDetails::status)
                .containsExactly(404, 409, 400, 415, 502, 403, 500);
        assertThat(problems)
                .extracting(ProblemDetails::title)
                .containsExactly(
                        "Not Found",
                        "Conflict",
                        "Bad Request",
                        "Unsupported Media Type",
                        "Bad Gateway",
                        "Forbidden",
                        "Internal Server Error");
        for (var problem : problems) {
            assertThat(problem.type()).isEqualTo("about:blank");
            assertThat(problem.instance()).isEqualTo(request.getRequestURI());
            var json = new ObjectMapper().valueToTree(problem);
            assertThat(json.has("traceId")).isFalse();
            assertThat(json.has("errors")).isFalse();
            assertThat(json.has("extensions")).isFalse();
        }
    }

    @Test
    void validationRetainsFieldContractAndCorrelatesCurrentTrace() {
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("trace-123");
        var binding = new BeanPropertyBindingResult(new Object(), "order");
        binding.addError(
                new FieldError("order", "quantity", -1, false, null, null, "must be positive"));

        var result =
                factory.validationError(
                        new MethodArgumentNotValidException(null, binding), request);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.title()).isEqualTo("Validation Failed");
        assertThat(result.detail()).contains("validation");
        assertThat(result.traceId()).isEqualTo("trace-123");
        assertThat(result.errors())
                .containsExactly(new ProblemDetails.FieldError("quantity", -1, "must be positive"));
    }
}

package com.example.omp.shared.web.error;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Builds {@link ProblemDetails} from various exception types.
 *
 * <p>Keeps the mapping logic out of the controller advice and testable in isolation.
 */
public class ProblemDetailsFactory {

    private final Tracer tracer;

    public ProblemDetailsFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    public ProblemDetails notFound(
            HttpStatus status, String title, String detail, HttpServletRequest request) {
        return build(status, title, detail, request.getRequestURI(), null);
    }

    public ProblemDetails validationError(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ProblemDetails.FieldError> errors =
                ex.getBindingResult().getFieldErrors().stream().map(this::toFieldError).toList();

        return build(
                HttpStatus.BAD_REQUEST,
                "Validation Failed",
                "One or more fields failed validation. See 'errors' for details.",
                request.getRequestURI(),
                errors);
    }

    public ProblemDetails conflict(String detail, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Conflict", detail, request.getRequestURI(), null);
    }

    public ProblemDetails badRequest(String detail, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", detail, request.getRequestURI(), null);
    }

    public ProblemDetails unsupportedMediaType(String detail, HttpServletRequest request) {
        return build(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Media Type",
                detail,
                request.getRequestURI(),
                null);
    }

    public ProblemDetails badGateway(String detail, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, "Bad Gateway", detail, request.getRequestURI(), null);
    }

    public ProblemDetails forbidden(String detail, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", detail, request.getRequestURI(), null);
    }

    public ProblemDetails internalError(String detail, HttpServletRequest request) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                detail,
                request.getRequestURI(),
                null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProblemDetails build(
            HttpStatus status,
            String title,
            String detail,
            String instance,
            List<ProblemDetails.FieldError> errors) {
        return new ProblemDetails(
                "about:blank",
                title,
                status.value(),
                detail,
                instance,
                currentTraceId(),
                errors,
                null);
    }

    private String currentTraceId() {
        var span = tracer == null ? null : tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }

    private ProblemDetails.FieldError toFieldError(FieldError fe) {
        return new ProblemDetails.FieldError(
                fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage());
    }
}

package com.example.omp.shared.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * RFC 7807 Problem Details for HTTP APIs.
 *
 * <p>Standard fields:
 *
 * <ul>
 *   <li>{@code type} — URI reference identifying the problem type (we use {@code about:blank} for
 *       simple cases).
 *   <li>{@code title} — short, human-readable summary.
 *   <li>{@code status} — HTTP status code.
 *   <li>{@code detail} — explanation specific to this occurrence.
 *   <li>{@code instance} — URI reference for this specific occurrence.
 * </ul>
 *
 * <p>Extension fields:
 *
 * <ul>
 *   <li>{@code traceId} — the distributed trace ID, for correlation.
 *   <li>{@code errors} — list of field-level errors (for 400 responses).
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "RFC 7807 problem details")
public record ProblemDetails(
        @Schema(description = "URI reference identifying the problem type", example = "about:blank")
                String type,
        @Schema(description = "Short, human-readable summary", example = "Not Found") String title,
        @Schema(description = "HTTP status code", example = "404") int status,
        @Schema(
                        description = "Explanation specific to this occurrence",
                        example = "Product not found: 00000000-0000-0000-0000-000000000201")
                String detail,
        @Schema(
                        description = "URI reference for this specific occurrence",
                        example = "/api/v1/products/00000000-0000-0000-0000-000000000201")
                String instance,
        @Schema(
                        description = "Trace ID for correlation",
                        example = "4bf92f3577b34da6a3ce929d0e0e4736")
                String traceId,
        @Schema(description = "Field-level errors (for 400 responses)") List<FieldError> errors,
        @Schema(description = "Additional properties, as allowed by RFC 7807")
                Map<String, Object> extensions) {

    @Schema(description = "A field-level validation error")
    public record FieldError(
            @Schema(description = "Field path", example = "sku") String field,
            @Schema(description = "Rejected value", example = "x") Object rejectedValue,
            @Schema(description = "Error message", example = "must be at least 4 characters")
                    String message) {}
}

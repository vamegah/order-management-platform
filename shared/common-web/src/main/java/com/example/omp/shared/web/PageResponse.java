package com.example.omp.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of results in a stable wire format.
 *
 * <p>We don't expose Spring Data's {@link Page} directly because its structure includes fields tied
 * to the library (pageable, sort object) that we don't want to commit to in the API.
 */
@Schema(description = "A page of results")
public record PageResponse<T>(
        @Schema(description = "Items on this page") List<T> content,
        @Schema(description = "Zero-based page index", example = "0") int page,
        @Schema(description = "Page size", example = "20") int size,
        @Schema(description = "Total items matching the query", example = "47") long totalElements,
        @Schema(description = "Total pages", example = "3") int totalPages,
        @Schema(description = "True if this is the first page") boolean first,
        @Schema(description = "True if this is the last page") boolean last) {

    /** Converts a Spring Data page to our wire format, mapping each element. */
    public static <E, D> PageResponse<D> from(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /** Converts a Spring Data page to our wire format without transformation. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }
}

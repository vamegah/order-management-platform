package com.example.omp.shared.web.pagination;

import com.example.omp.shared.web.error.InvalidPaginationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Resolves pagination query parameters to a Spring Data {@link Pageable}.
 *
 * <p>Enforces:
 *
 * <ul>
 *   <li>Page size is between 1 and {@code app.pagination.max-size}.
 *   <li>Sort fields are in the allowed list (prevents SQL injection via sort field names).
 *   <li>Default sort is by {@code createdAt} descending.
 * </ul>
 */
public class PageableResolver {

    private final PaginationProperties config;

    public PageableResolver(PaginationProperties config) {
        this.config = config;
    }

    /**
     * @param page zero-based page number; null = 0
     * @param size page size; null = config.defaultSize
     * @param sort comma-separated fields with optional direction, e.g. {@code "name,asc"} or {@code
     *     "price,desc,name,asc"}; null = {@code createdAt,desc}
     */
    public Pageable resolve(Integer page, Integer size, String sort) {
        int pageNumber = page != null ? page : 0;
        int pageSize = size != null ? size : config.getDefaultSize();

        if (pageNumber < 0) {
            throw new InvalidPaginationException("Page number must be >= 0");
        }
        if (pageSize < 1) {
            throw new InvalidPaginationException("Page size must be >= 1");
        }
        if (pageSize > config.getMaxSize()) {
            throw new InvalidPaginationException(
                    "Page size must be <= "
                            + config.getMaxSize()
                            + " (requested "
                            + pageSize
                            + ")");
        }

        return PageRequest.of(pageNumber, pageSize, parseSort(sort));
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) sort = "createdAt,desc";
        if (sort.length() > 1024)
            throw new InvalidPaginationException("Sort expression is too long");

        List<Sort.Order> orders = new ArrayList<>();
        String[] parts = sort.split(",", -1);
        var fields = new HashSet<String>();

        // "name,asc" or "name" (defaults to asc)
        for (int i = 0; i < parts.length; i += 2) {
            String field = parts[i].trim();
            if (!fields.add(field)) {
                throw new InvalidPaginationException("Duplicate sort field: " + field);
            }
            if (!config.getAllowedSortFields().contains(field)) {
                throw new InvalidPaginationException(
                        "Cannot sort by '"
                                + field
                                + "'. Allowed: "
                                + config.getAllowedSortFields());
            }

            Sort.Direction direction = Sort.Direction.ASC;
            if (i + 1 < parts.length) {
                String dir = parts[i + 1].trim().toLowerCase(Locale.ROOT);
                if ("desc".equals(dir)) {
                    direction = Sort.Direction.DESC;
                } else if (!"asc".equals(dir)) {
                    throw new InvalidPaginationException(
                            "Invalid sort direction '" + dir + "'. Use 'asc' or 'desc'.");
                }
            }
            orders.add(new Sort.Order(direction, field));
        }
        return Sort.by(orders);
    }
}

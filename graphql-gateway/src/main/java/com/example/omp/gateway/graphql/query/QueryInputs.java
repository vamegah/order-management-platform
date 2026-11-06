package com.example.omp.gateway.graphql.query;

import java.util.Map;
import java.util.UUID;

public final class QueryInputs {
    private QueryInputs() {}

    public static String text(Map<String, Object> filter, String field) {
        return filter == null || filter.get(field) == null ? null : filter.get(field).toString();
    }

    public static UUID id(Map<String, Object> filter, String field) {
        String value = text(filter, field);
        return value == null ? null : UUID.fromString(value);
    }

    public static int page(Integer value) {
        if (value != null && value < 0)
            throw new IllegalArgumentException("Page must be nonnegative");
        return value == null ? 0 : value;
    }

    public static int size(Integer value) {
        if (value != null && (value < 1 || value > 100))
            throw new IllegalArgumentException("Size must be between 1 and 100");
        return value == null ? 20 : value;
    }
}

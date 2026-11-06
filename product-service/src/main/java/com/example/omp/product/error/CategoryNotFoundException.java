package com.example.omp.product.error;

import java.util.UUID;

/**
 * Thrown when a referenced category doesn't exist.
 *
 * <p>Translated to HTTP 404 (or 400, depending on context — a missing category in a filter is 400;
 * a missing category in a path is 404) by the global exception handler.
 */
public class CategoryNotFoundException extends RuntimeException {

    private final String identifier;

    public CategoryNotFoundException(UUID id) {
        super("Category not found: " + id);
        this.identifier = id.toString();
    }

    public CategoryNotFoundException(String slug) {
        super("Category not found with slug: " + slug);
        this.identifier = slug;
    }

    public String getIdentifier() {
        return identifier;
    }
}

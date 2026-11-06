package com.example.omp.product.error;

import java.util.UUID;

/**
 * Thrown when a requested product doesn't exist.
 *
 * <p>Translated to HTTP 404 by the global exception handler (Batch 1.4).
 */
public class ProductNotFoundException extends RuntimeException {

    private final String identifier;

    /** Constructs from a UUID. */
    public ProductNotFoundException(UUID id) {
        super("Product not found: " + id);
        this.identifier = id.toString();
    }

    /** Constructs from a SKU. */
    public ProductNotFoundException(String sku) {
        super("Product not found with SKU: " + sku);
        this.identifier = sku;
    }

    /** The identifier (ID or SKU) that wasn't found. */
    public String getIdentifier() {
        return identifier;
    }
}

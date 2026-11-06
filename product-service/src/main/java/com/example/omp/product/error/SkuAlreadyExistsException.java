package com.example.omp.product.error;

/**
 * Thrown when attempting to create a product with a SKU that already exists.
 *
 * <p>Translated to HTTP 409 by the global exception handler.
 */
public class SkuAlreadyExistsException extends RuntimeException {

    private final String sku;

    public SkuAlreadyExistsException(String sku) {
        super("A product with SKU '" + sku + "' already exists");
        this.sku = sku;
    }

    public String getSku() {
        return sku;
    }
}

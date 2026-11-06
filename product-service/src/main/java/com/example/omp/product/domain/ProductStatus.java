package com.example.omp.product.domain;

/**
 * Product lifecycle state.
 *
 * <p>Values must match the {@code product_status_valid} check constraint in V1__initial_schema.sql.
 * Adding a new value requires both an enum change and a migration to update the constraint.
 */
public enum ProductStatus {

    /** Visible to customers. Default for new products. */
    ACTIVE,

    /** Hidden. Used for drafts or temporarily unavailable products. */
    INACTIVE,

    /** No longer sold. Kept for historical order references. */
    DISCONTINUED
}

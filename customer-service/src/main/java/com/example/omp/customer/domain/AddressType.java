package com.example.omp.customer.domain;

/**
 * Address type: what the address is used for.
 *
 * <p>Values must match the {@code address_type_valid} check constraint in V1__initial_schema.sql.
 */
public enum AddressType {

    /** Used only as a billing address. */
    BILLING,

    /** Used only as a shipping address. */
    SHIPPING,

    /** Used as both. Common for customers with a single address. */
    BOTH
}

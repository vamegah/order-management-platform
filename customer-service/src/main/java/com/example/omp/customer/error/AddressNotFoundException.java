package com.example.omp.customer.error;

import java.util.UUID;

/**
 * Thrown when a referenced address doesn't exist, or doesn't belong to the specified customer.
 * Translated to HTTP 404.
 */
public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(UUID customerId, UUID addressId) {
        super("Address " + addressId + " not found for customer " + customerId);
    }

    public AddressNotFoundException(UUID addressId) {
        super("Address not found: " + addressId);
    }

    public Object getIdentifier() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}

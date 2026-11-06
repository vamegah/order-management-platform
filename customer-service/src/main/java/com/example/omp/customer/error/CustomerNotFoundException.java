package com.example.omp.customer.error;

import java.util.UUID;

/**
 * Thrown when a requested customer doesn't exist. Translated to HTTP 404 by the global exception
 * handler.
 */
public class CustomerNotFoundException extends RuntimeException {

    private final String identifier;

    public CustomerNotFoundException(UUID id) {
        super("Customer not found: " + id);
        this.identifier = id.toString();
    }

    public CustomerNotFoundException(String email) {
        super("Customer not found with email: " + email);
        this.identifier = email;
    }

    public String getIdentifier() {
        return identifier;
    }
}

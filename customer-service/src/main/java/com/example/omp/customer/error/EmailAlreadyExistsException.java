package com.example.omp.customer.error;

/**
 * Thrown when attempting to create a customer with an email that already exists. Translated to HTTP
 * 409.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    private final String email;

    public EmailAlreadyExistsException(String email) {
        super("A customer with email '" + email + "' already exists");
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}

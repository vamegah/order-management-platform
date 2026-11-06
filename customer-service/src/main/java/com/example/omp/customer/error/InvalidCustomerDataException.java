package com.example.omp.customer.error;

/** Thrown for business-rule violations not caught by bean validation. Translated to HTTP 400. */
public class InvalidCustomerDataException extends RuntimeException {

    public InvalidCustomerDataException(String message) {
        super(message);
    }

    public InvalidCustomerDataException(String message, Throwable cause) {
        super(message, cause);
    }
}

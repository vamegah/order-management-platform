package com.example.omp.gateway.graphql.error;

public class ClientValidationException extends RuntimeException {
    public ClientValidationException(String message) {
        super(message);
    }
}

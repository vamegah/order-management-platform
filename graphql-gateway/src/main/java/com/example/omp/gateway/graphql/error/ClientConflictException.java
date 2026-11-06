package com.example.omp.gateway.graphql.error;

public class ClientConflictException extends RuntimeException {
    public ClientConflictException(String message) {
        super(message);
    }
}

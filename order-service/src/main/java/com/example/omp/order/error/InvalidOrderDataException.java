package com.example.omp.order.error;

public class InvalidOrderDataException extends RuntimeException {
    public InvalidOrderDataException(String message) {
        super(message);
    }

    public InvalidOrderDataException(String message, Throwable cause) {
        super(message, cause);
    }
}

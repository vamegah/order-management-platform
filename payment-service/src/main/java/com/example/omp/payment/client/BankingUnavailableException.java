package com.example.omp.payment.client;

public class BankingUnavailableException extends RuntimeException {
    public BankingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

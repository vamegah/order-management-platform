package com.example.omp.bank.service;

public class BankingFaultException extends RuntimeException {

    private final String code;

    public BankingFaultException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

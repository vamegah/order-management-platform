package com.example.omp.adapter.port;

/**
 * Protocol-agnostic banking failure. Carries a stable code that the REST layer maps to a Problem
 * Details type.
 */
public class BankingException extends RuntimeException {

    public enum Code {
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND,
        DAILY_LIMIT_EXCEEDED,
        INVALID_ACCOUNT_FORMAT,
        UNSUPPORTED_CURRENCY,
        UPSTREAM_UNAVAILABLE,
        UPSTREAM_ERROR
    }

    private final Code code;

    public BankingException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public BankingException(Code code, String message) {
        this(code, message, null);
    }

    public Code getCode() {
        return code;
    }
}

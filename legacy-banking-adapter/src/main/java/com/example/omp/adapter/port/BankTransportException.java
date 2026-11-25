package com.example.omp.adapter.port;

/**
 * Only connection/read failures may retry; durable request identities make ambiguous writes safe.
 */
public class BankTransportException extends BankingException {
    public BankTransportException(Code code, Throwable cause) {
        super(code, "Bank service unavailable", cause);
    }
}

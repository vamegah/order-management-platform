package com.example.omp.payment.provider.stripe;

import com.fasterxml.jackson.databind.JsonNode;

public class StripeApiException extends RuntimeException {

    private final int httpStatus;
    private final JsonNode body;

    public StripeApiException(int httpStatus, String message, JsonNode body) {
        super(message);
        this.httpStatus = httpStatus;
        this.body = body;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public JsonNode getBody() {
        return body;
    }

    public boolean isRetryable() {
        return httpStatus == 0 || httpStatus == 429 || httpStatus >= 500;
    }
}

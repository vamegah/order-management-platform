package com.example.omp.notification.webhook.delivery;

/**
 * The result of one HTTP delivery attempt.
 *
 * <p>httpStatus is null when the request never completed (timeout, connection refused, TLS error).
 */
public record DeliveryResult(
        DeliveryOutcome outcome,
        Integer httpStatus,
        String responseBody,
        String errorMessage,
        Long retryAfterMillis) {

    public static DeliveryResult success(int status, String body) {
        return new DeliveryResult(DeliveryOutcome.SUCCESS, status, body, null, null);
    }

    public static DeliveryResult retryable(int status, String body) {
        return new DeliveryResult(DeliveryOutcome.RETRYABLE, status, body, null, null);
    }

    public static DeliveryResult retryable(int status, String body, Long retryAfterMillis) {
        return new DeliveryResult(DeliveryOutcome.RETRYABLE, status, body, null, retryAfterMillis);
    }

    public static DeliveryResult retryableError(String error) {
        return new DeliveryResult(DeliveryOutcome.RETRYABLE, null, null, error, null);
    }

    public static DeliveryResult permanent(int status, String body) {
        return new DeliveryResult(DeliveryOutcome.PERMANENT, status, body, null, null);
    }

    public static DeliveryResult permanentError(String error) {
        return new DeliveryResult(DeliveryOutcome.PERMANENT, null, null, error, null);
    }
}

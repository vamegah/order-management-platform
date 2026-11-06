package com.example.omp.notification.webhook.delivery;

/** Classification of a single delivery attempt. */
public enum DeliveryOutcome {
    /** 2xx response. No retry. */
    SUCCESS,
    /** 5xx, timeout, connection refused, 408, 429, 401/403. Retry. */
    RETRYABLE,
    /** 4xx other than 408/429/401/403, TLS handshake failure. No retry. */
    PERMANENT
}

package com.example.omp.notification.webhook;

public enum DeliveryStatus {
    PENDING,
    SUCCESS,
    FAILED_RETRYABLE,
    FAILED_PERMANENT
}

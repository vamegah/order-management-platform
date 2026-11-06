package com.example.omp.notification.webhook.dto;

import java.util.UUID;

/** Returned only on create and rotate-secret. The secret is shown once and never again. */
public record SubscriptionSecretResponse(UUID id, String secret) {}

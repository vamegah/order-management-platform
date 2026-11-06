package com.example.omp.notification.webhook;

import java.util.UUID;

/** The resolved owner (from JWT) of a subscription request. */
public record SubscriptionOwner(SubscriptionOwnerType type, UUID id) {}

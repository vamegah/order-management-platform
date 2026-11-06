package com.example.omp.payment.provider;

import java.util.Optional;

/**
 * Result of verifying an inbound PSP webhook.
 *
 * <p>If valid: {@code valid == true} and {@code event} is present. If invalid: {@code valid ==
 * false} and {@code reason} explains why.
 */
public record ProviderWebhookVerification(
        boolean valid, String reason, ProviderWebhookEvent event) {

    public static ProviderWebhookVerification invalid(String reason) {
        return new ProviderWebhookVerification(false, reason, null);
    }

    public static ProviderWebhookVerification valid(ProviderWebhookEvent event) {
        return new ProviderWebhookVerification(true, null, event);
    }

    public Optional<ProviderWebhookEvent> eventOpt() {
        return Optional.ofNullable(event);
    }
}

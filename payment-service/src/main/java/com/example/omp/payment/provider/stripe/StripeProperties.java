package com.example.omp.payment.provider.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "payment.stripe")
public class StripeProperties {

    /** Secret API key (sk_...). Loaded from env. */
    private String apiKey;

    /** Webhook signing secret (whsec_...). Loaded from env. */
    private String webhookSecret;

    /** Stripe API version to pin. */
    private String apiVersion = "2024-06-20";

    /** Per-request timeout. */
    private int timeoutSeconds = 15;

    /** Network-level retries on top of the first attempt. */
    private int maxNetworkRetries = 2;

    /** Webhook signature tolerance window. */
    private int webhookToleranceSeconds = 300;

    // --- getters / setters ---

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxNetworkRetries() {
        return maxNetworkRetries;
    }

    public void setMaxNetworkRetries(int maxNetworkRetries) {
        this.maxNetworkRetries = maxNetworkRetries;
    }

    public int getWebhookToleranceSeconds() {
        return webhookToleranceSeconds;
    }

    public void setWebhookToleranceSeconds(int webhookToleranceSeconds) {
        this.webhookToleranceSeconds = webhookToleranceSeconds;
    }
}

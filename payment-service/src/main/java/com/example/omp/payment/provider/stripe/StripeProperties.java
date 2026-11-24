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

    private String apiBaseUrl = "https://api.stripe.com/v1";
    private boolean simulatorEnabled;
    private String apiKeyFile;
    private String webhookSecretFile;
    private String previousWebhookSecret;
    private String previousWebhookSecretFile;
    private java.time.Instant previousWebhookSecretValidUntil;

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String v) {
        apiBaseUrl = v;
    }

    public boolean isSimulatorEnabled() {
        return simulatorEnabled;
    }

    public void setSimulatorEnabled(boolean v) {
        simulatorEnabled = v;
    }

    public void setApiKeyFile(String v) {
        apiKeyFile = v;
    }

    public void setWebhookSecretFile(String v) {
        webhookSecretFile = v;
    }

    public void setPreviousWebhookSecret(String v) {
        previousWebhookSecret = v;
    }

    public void setPreviousWebhookSecretFile(String v) {
        previousWebhookSecretFile = v;
    }

    public void setPreviousWebhookSecretValidUntil(java.time.Instant v) {
        previousWebhookSecretValidUntil = v;
    }

    public java.util.List<String> webhookKeys() {
        var keys = new java.util.ArrayList<String>();
        String current = getWebhookSecret();
        if (current != null && !current.isBlank()) keys.add(current);
        if (previousWebhookSecretValidUntil != null
                && java.time.Instant.now().isBefore(previousWebhookSecretValidUntil)) {
            String previous = secret(previousWebhookSecretFile, previousWebhookSecret);
            if (previous != null && !previous.isBlank()) keys.add(previous);
        }
        return java.util.List.copyOf(keys);
    }

    private static String secret(String file, String fallback) {
        if (file == null || file.isBlank()) return fallback;
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(file))) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IllegalStateException();
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (Exception failure) {
            throw new IllegalStateException("Provider credential file unavailable");
        }
    }

    // --- getters / setters ---

    public String getApiKey() {
        return secret(apiKeyFile, apiKey);
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWebhookSecret() {
        return secret(webhookSecretFile, webhookSecret);
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

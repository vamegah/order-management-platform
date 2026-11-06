package com.example.omp.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the payment provider simulator.
 *
 * <p>Binds to {@code omp.simulator.*} in application.yml.
 */
@ConfigurationProperties(prefix = "omp.simulator")
public class SimulatorProperties {

    /**
     * The URL where webhooks are POSTed. Typically payment-service's {@code /webhooks/payment}
     * endpoint.
     */
    private String webhookUrl = "http://payment-service:9093/webhooks/payment";

    /**
     * HMAC secret used to sign outgoing webhooks. Must match the secret configured in
     * payment-service's receiver.
     *
     * <p>In dev, this is a fixed public value (the simulator isn't a security boundary). In prod,
     * the simulator is not deployed.
     */
    private String hmacSecret = "dev-simulator-secret-not-for-production";

    /**
     * How long to wait before sending the webhook after a payment is initiated. Simulates provider
     * round-trip latency.
     *
     * <p>Tests can set this to 0 for immediate delivery.
     */
    private long webhookDelayMs = 2000L;

    /**
     * Probability that a simulated payment should fail (0.0 to 1.0). Used in tests to verify
     * failure handling.
     */
    private double failureRate = 0.0;

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public long getWebhookDelayMs() {
        return webhookDelayMs;
    }

    public void setWebhookDelayMs(long webhookDelayMs) {
        this.webhookDelayMs = webhookDelayMs;
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }
}

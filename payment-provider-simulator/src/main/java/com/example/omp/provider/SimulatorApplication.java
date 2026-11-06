package com.example.omp.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Main entry point for the payment provider simulator.
 *
 * <p>Simulates an external payment provider for local development and end-to-end testing.
 *
 * <p>The simulator exposes HTTP endpoints that trigger simulated payment flows. After a
 * configurable delay, it sends a signed webhook to the configured callback URL — typically
 * payment-service's {@code /webhooks/payment} endpoint.
 *
 * <p><b>HMAC signing format:</b> The simulator uses the same signature format as Stripe and other
 * production providers:
 *
 * <pre>
 *   signature = HMAC-SHA256(secret, timestamp + "." + raw_body)
 * </pre>
 *
 * The signature is sent in the {@code X-Signature-256} header as {@code sha256=<hex>}. The
 * timestamp is sent in {@code X-Timestamp}.
 *
 * <p>Because the simulator's wire format matches real providers, the payment-service integration
 * code is identical to what a real provider integration would use. Swapping the simulator for
 * Stripe in production is a configuration change (URL, secret), not a code change.
 *
 * <p><b>NOT FOR PRODUCTION.</b> The simulator has no authentication, no rate limiting, and uses a
 * fixed secret. It is designed for dev and test environments only.
 *
 * @see com.example.omp.provider.WebhookSender
 * @see com.example.omp.provider.PaymentSimulationController
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}

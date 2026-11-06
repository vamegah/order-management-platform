package com.example.omp.payment.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> charges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> webhooks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> chargeTimers = new ConcurrentHashMap<>();
    private final Counter webhookSignatureFailures;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.webhookSignatureFailures =
                Counter.builder("payment_provider_webhook_verification_failures_total")
                        .description("PSP webhook signature verification failures")
                        .tag("provider", "stripe")
                        .register(registry);
    }

    public void recordCharge(String provider, String result) {
        charges.computeIfAbsent(
                        provider + "|" + result,
                        k ->
                                Counter.builder("payment_provider_charge_total")
                                        .description("Payment provider charge attempts")
                                        .tag("provider", provider)
                                        .tag("result", result)
                                        .register(registry))
                .increment();
    }

    public Timer chargeTimer(String provider) {
        return chargeTimers.computeIfAbsent(
                provider,
                k ->
                        Timer.builder("payment_provider_charge_duration_seconds")
                                .description("Payment provider charge latency")
                                .tag("provider", provider)
                                .register(registry));
    }

    public void recordChargeDuration(String provider, Duration duration) {
        chargeTimer(provider).record(duration);
    }

    public void recordWebhook(String provider, String result) {
        webhooks.computeIfAbsent(
                        provider + "|" + result,
                        k ->
                                Counter.builder("payment_provider_webhook_total")
                                        .description("PSP webhook deliveries by result")
                                        .tag("provider", provider)
                                        .tag("result", result)
                                        .register(registry))
                .increment();
    }

    public void recordWebhookSignatureFailure() {
        webhookSignatureFailures.increment();
    }
}

package com.example.omp.payment.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentWebhookWorker {
    private final PaymentWebhookInbox inbox;
    private final MeterRegistry metrics;
    private final boolean enabled;

    public PaymentWebhookWorker(
            PaymentWebhookInbox inbox,
            MeterRegistry metrics,
            @Value("${payment.webhook.worker-enabled:true}") boolean enabled) {
        this.inbox = inbox;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${payment.webhook.worker-delay-ms:250}")
    public void drain() {
        if (!enabled || Thread.currentThread().isInterrupted()) return;
        for (var id : inbox.due()) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                if (inbox.process(id))
                    metrics.counter("payment.webhook.inbox.processed").increment();
            } catch (RuntimeException unresolved) {
                inbox.failed(id);
                metrics.counter("payment.webhook.inbox.retries").increment();
            }
        }
    }

    @Scheduled(fixedDelayString = "${payment.webhook.cleanup-delay-ms:3600000}")
    public void cleanup() {
        if (enabled) inbox.cleanup();
    }
}

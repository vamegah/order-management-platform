package com.example.omp.payment.provider.noop;

import static com.example.omp.payment.provider.ProviderWebhookVerification.*;

import com.example.omp.payment.provider.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory provider for local dev and tests.
 *
 * <p>Behavior: - charge: succeeds after a short delay, returns a synthetic reference. If the
 * idempotency key is reused, returns the same reference. - refund: always succeeds. -
 * verifyWebhook: accepts any body carrying X-Noop-Signature matching the configured dev secret.
 *
 * <p>Failure simulation (for tests): - amount ending in 00 (i.e., exact hundreds) -> declined -
 * amount ending in 99 -> error (retryable)
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "noop", matchIfMissing = true)
public class NoopPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopPaymentProvider.class);

    private final ObjectMapper objectMapper;
    private final java.util.concurrent.ConcurrentHashMap<String, String> idempotencyStore =
            new java.util.concurrent.ConcurrentHashMap<>();

    public NoopPaymentProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "noop";
    }

    @Override
    public ProviderChargeResult charge(ChargeRequest request) {
        String existing = idempotencyStore.get(request.idempotencyKey());
        if (existing != null) {
            log.debug("Noop provider: idempotent hit for {}", request.idempotencyKey());
            return ProviderChargeResult.succeeded(existing);
        }

        sleepQuietly(50);

        long amount = request.amountMinor();
        if (amount % 100 == 0) {
            return ProviderChargeResult.declined(
                    "card_declined", "Simulated decline (amount divisible by 100)");
        }
        if (amount % 100 == 99) {
            return ProviderChargeResult.error("Simulated provider error (amount ending 99)");
        }

        String ref = "noop_pi_" + UUID.randomUUID();
        idempotencyStore.put(request.idempotencyKey(), ref);
        return ProviderChargeResult.succeeded(ref);
    }

    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        String existing = idempotencyStore.get(request.idempotencyKey());
        if (existing != null) {
            return ProviderRefundResult.succeeded(existing);
        }
        sleepQuietly(30);
        String ref = "noop_re_" + UUID.randomUUID();
        idempotencyStore.put(request.idempotencyKey(), ref);
        return ProviderRefundResult.succeeded(ref);
    }

    @Override
    public ProviderRefundResult lookupRefund(RefundRequest request, String providerRefundId) {
        String reference = idempotencyStore.get(request.idempotencyKey());
        return reference != null && reference.equals(providerRefundId)
                ? ProviderRefundResult.succeeded(reference)
                : ProviderRefundResult.error("Development refund ledger entry unavailable");
    }

    @Override
    public ProviderWebhookVerification verifyWebhook(String rawBody, Map<String, String> headers) {
        String sig = headerIgnoreCase(headers, "X-Noop-Signature");
        if (sig == null || !sig.equals("noop-dev-secret")) {
            return ProviderWebhookVerification.invalid("bad_noop_signature");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventId = root.path("id").asText(null);
            String eventType = root.path("type").asText(null);
            JsonNode data = root.path("data").path("object");
            String paymentRef = data.path("payment_intent").asText(data.path("id").asText(null));
            if (eventId == null || eventType == null) {
                return ProviderWebhookVerification.invalid("missing_event_id_or_type");
            }
            return ProviderWebhookVerification.valid(
                    new ProviderWebhookEvent(eventId, eventType, data, paymentRef));
        } catch (Exception e) {
            return ProviderWebhookVerification.invalid("unparseable_body");
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String headerIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}

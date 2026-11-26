package com.example.omp.shared.web.quota;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Does not retain or label raw subjects, paths, addresses or credentials. */
public final class QuotaService {
    public record Decision(boolean allowed, boolean available, long retryAfterSeconds) {}

    private final QuotaStore store;
    private final QuotaProperties properties;
    private final MeterRegistry metrics;

    public QuotaService(QuotaStore store, QuotaProperties properties, MeterRegistry metrics) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Decision acquire(QuotaPolicy policy, String issuer, String subject) {
        if (!properties.enabled()) return new Decision(true, true, 0);
        Decision decision;
        try {
            long wait =
                    store.acquire(
                            key(policy, issuer, subject), policy.capacity(properties.multiplier()));
            decision = new Decision(wait == 0, true, Math.max(1, (wait + 999) / 1000));
        } catch (RuntimeException failure) {
            // Quotas must never become replica-local unlimited access when the store fails.
            decision = new Decision(false, false, 1);
        }
        metrics.counter(
                        "omp.quota.decisions",
                        "policy",
                        policy.name().toLowerCase(java.util.Locale.ROOT),
                        "outcome",
                        decision.allowed()
                                ? "allowed"
                                : decision.available() ? "limited" : "unavailable")
                .increment();
        return decision;
    }

    String key(QuotaPolicy policy, String issuer, String subject) {
        String identity =
                issuer != null && subject != null && !subject.isBlank()
                        ? issuer + "\u0000" + subject
                        : "anonymous";
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8));
            // Full redacted identities remain independent. Redis bounds and expires the active
            // identity catalogue atomically.
            return "omp:quota:v1:{"
                    + properties.namespace()
                    + "_"
                    + policy.name()
                    + "}:"
                    + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

package com.example.omp.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable, bounded deterministic fixture. Atomic replacement keeps provider IDs across restarts.
 */
@Service
public class ProviderFixtureLedger {
    private final Path directory;
    private final ObjectMapper mapper;
    private final Map<String, ObjectNode> resources = new HashMap<>();
    private ObjectNode controls;

    public ProviderFixtureLedger(
            ObjectMapper mapper,
            Environment environment,
            @Value("${omp.simulator.data-directory:./.scratch/provider-fixture}") String data)
            throws Exception {
        var profiles = Set.of(environment.getActiveProfiles());
        if (profiles.contains("prod")
                || profiles.contains("production")
                || Collections.disjoint(
                        profiles, Set.of("local", "dev", "test", "acceptance", "e2e")))
            throw new IllegalArgumentException(
                    "Payment simulator requires a development/test profile");
        this.mapper = mapper;
        directory = Path.of(data).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (var file :
                    files.filter(
                                    p ->
                                            p.getFileName()
                                                    .toString()
                                                    .matches("(?:pi|re)_[a-f0-9]{32}\\.json"))
                            .limit(5001)
                            .toList()) {
                if (resources.size() >= 5000)
                    throw new IllegalStateException("Simulator ledger capacity exceeded");
                var value = (ObjectNode) mapper.readTree(Files.readString(file));
                resources.put(value.path("id").asText(), value);
            }
        }
        controls =
                Files.exists(directory.resolve("controls.json"))
                        ? (ObjectNode)
                                mapper.readTree(
                                        Files.readString(directory.resolve("controls.json")))
                        : mapper.createObjectNode();
    }

    public synchronized JsonNode charge(Map<String, String> request, String key) {
        String id = identity("pi", key);
        ObjectNode previous = resources.get(id);
        if (previous != null) return duplicate(previous, request);
        capacity();
        long amount = positive(request.get("amount"));
        String currency = request.getOrDefault("currency", "");
        if (!currency.matches("[a-zA-Z]{3}")) throw bad("Invalid currency");
        String method = request.getOrDefault("payment_method", "");
        if (!method.matches("pm_[A-Za-z0-9_]{1,240}")) throw bad("Tokenized method required");
        var payment = mapper.createObjectNode();
        payment.put("id", id)
                .put("amount", amount)
                .put("currency", currency.toLowerCase(Locale.ROOT))
                .put("payment_method", method);
        var metadata = payment.putObject("metadata");
        for (String field : List.of("payment_id", "order_id", "customer_id")) {
            String value = request.get("metadata[" + field + "]");
            try {
                UUID.fromString(value);
            } catch (Exception invalid) {
                throw bad("Correlation metadata required");
            }
            metadata.put(field, value);
        }
        payment.put("_requestHash", digest(request));
        payment.putArray("_events");
        String status =
                switch (method) {
                    case "pm_decline" -> "requires_payment_method";
                    case "pm_action" -> "requires_action";
                    case "pm_processing", "pm_fail_late" -> "processing";
                    default -> "succeeded";
                };
        payment.put("status", status);
        if ("requires_action".equals(status)) {
            payment.put("client_secret", id + "_secret_simulator");
            payment.putObject("next_action").put("type", "use_stripe_sdk");
        }
        if ("requires_payment_method".equals(status))
            decline(payment, "card_declined", "Payment method declined");
        if ("processing".equals(status))
            payment.put("_completeAt", System.currentTimeMillis() + delay())
                    .put(
                            "_finalStatus",
                            method.equals("pm_fail_late")
                                    ? "requires_payment_method"
                                    : "succeeded");
        enqueue(payment, eventType(payment), delay());
        save(payment);
        resources.put(id, payment);
        return publicResource(payment);
    }

    public synchronized JsonNode refund(Map<String, String> request, String key) {
        String id = identity("re", key);
        ObjectNode previous = resources.get(id);
        if (previous != null) return duplicate(previous, request);
        capacity();
        String paymentId = request.get("payment_intent");
        ObjectNode payment = resources.get(paymentId);
        if (payment == null || !"succeeded".equals(payment.path("status").asText()))
            throw bad("Captured payment required");
        long amount = positive(request.get("amount"));
        long reserved = 0;
        for (var row : resources.values())
            if (paymentId.equals(row.path("payment_intent").asText())
                    && !Set.of("failed", "canceled").contains(row.path("status").asText()))
                reserved = Math.addExact(reserved, row.path("amount").longValue());
        if (amount > payment.path("amount").longValue() - reserved)
            throw bad("Refund exceeds captured balance");
        var refund = mapper.createObjectNode();
        refund.put("id", id)
                .put("payment_intent", paymentId)
                .put("amount", amount)
                .put("currency", payment.path("currency").asText())
                .put("status", "pending")
                .put("_requestHash", digest(request));
        var metadata = refund.putObject("metadata");
        for (String field : List.of("payment_id", "refund_id")) {
            String value = request.get("metadata[" + field + "]");
            try {
                UUID.fromString(value);
            } catch (Exception invalid) {
                throw bad("Refund correlation metadata required");
            }
            metadata.put(field, value);
        }
        refund.putArray("_events");
        refund.put("_completeAt", System.currentTimeMillis() + delay())
                .put("_finalStatus", controls.path("refundOutcome").asText("succeeded"));
        enqueue(refund, "refund.created", 0);
        save(refund);
        resources.put(id, refund);
        return publicResource(refund);
    }

    public synchronized JsonNode cancel(String id) {
        ObjectNode original = resources.get(id);
        if (original == null) throw bad("Unknown intent");
        ObjectNode row = original.deepCopy();
        if (!Set.of("requires_action", "processing", "requires_payment_method", "canceled")
                .contains(row.path("status").asText()))
            throw bad("Captured payment cannot be canceled");
        if (!"canceled".equals(row.path("status").asText())) {
            row.put("status", "canceled");
            row.remove("_completeAt");
            enqueue(row, "payment_intent.canceled", 0);
            save(row);
            resources.put(id, row);
        }
        return publicResource(row);
    }

    public synchronized JsonNode get(String id) {
        var row = resources.get(id);
        if (row == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown fixture reference");
        return publicResource(row);
    }

    private void decline(ObjectNode payment, String code, String message) {
        String method = payment.path("payment_method").asText();
        payment.putNull("payment_method");
        payment.putObject("last_payment_error")
                .put("decline_code", code)
                .put("message", message)
                .putObject("payment_method")
                .put("id", method);
    }

    public synchronized JsonNode complete(String id, boolean success) {
        ObjectNode stored = resources.get(id);
        ObjectNode row = stored == null ? null : stored.deepCopy();
        if (row == null
                || !id.startsWith("pi_")
                || !"requires_action".equals(row.path("status").asText()))
            throw bad("Action is not pending");
        row.put("status", success ? "succeeded" : "requires_payment_method");
        row.remove("next_action");
        if (!success) decline(row, "authentication_failed", "Authentication failed");
        enqueue(row, eventType(row), delay());
        save(row);
        resources.put(id, row);
        return publicResource(row);
    }

    public synchronized JsonNode configure(JsonNode value) {
        if (value == null || !value.isObject()) throw bad("Object controls required");
        for (String field : List.of("dropCallbacks", "reverseCallbacks"))
            if (value.has(field) && !value.path(field).isBoolean())
                throw bad("Boolean control required");
        Set<String> fields =
                Set.of(
                        "delayMs",
                        "dropCallbacks",
                        "duplicateCallbacks",
                        "reverseCallbacks",
                        "refundOutcome",
                        "responseDelayMs");
        value.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!fields.contains(k)) throw bad("Unknown fixture control");
                        });
        if (value.has("delayMs")
                && (!value.path("delayMs").canConvertToLong()
                        || value.path("delayMs").asLong() < 0
                        || value.path("delayMs").asLong() > 30000)) throw bad("Invalid delay");
        if (value.has("responseDelayMs")
                && (!value.path("responseDelayMs").canConvertToLong()
                        || value.path("responseDelayMs").asLong() < 0
                        || value.path("responseDelayMs").asLong() > 5000))
            throw bad("Invalid response delay");
        if (value.has("refundOutcome")
                && !Set.of("succeeded", "failed").contains(value.path("refundOutcome").asText()))
            throw bad("Invalid refund outcome");
        if (value.has("duplicateCallbacks")
                && (!value.path("duplicateCallbacks").isIntegralNumber()
                        || value.path("duplicateCallbacks").asInt() < 0
                        || value.path("duplicateCallbacks").asInt() > 3))
            throw bad("Invalid duplicate count");
        ObjectNode next = controls.deepCopy();
        next.setAll((ObjectNode) value);
        write("controls.json", next);
        controls = next;
        return controls.deepCopy();
    }

    public synchronized long responseDelay() {
        return controls.path("responseDelayMs").asLong(0);
    }

    public synchronized Map<String, Object> stats() {
        return Map.of(
                "resources",
                resources.size(),
                "payments",
                resources.keySet().stream().filter(k -> k.startsWith("pi_")).count(),
                "refunds",
                resources.keySet().stream().filter(k -> k.startsWith("re_")).count(),
                "pendingCallbacks",
                resources.values().stream()
                        .flatMap(r -> r.withArray("_events").valueStream())
                        .filter(e -> !e.path("_sent").asBoolean())
                        .count());
    }

    public record Callback(String resource, String event, JsonNode payload, int duplicates) {}

    public synchronized List<Callback> due() {
        return due(20);
    }

    public synchronized List<Callback> due(int maximum) {
        if (maximum < 1 || maximum > 20) return List.of();
        long now = System.currentTimeMillis();
        var due = new ArrayList<Callback>();
        for (var original : new ArrayList<>(resources.values())) {
            boolean completes =
                    original.has("_completeAt") && original.path("_completeAt").asLong() <= now;
            boolean claims =
                    !controls.path("dropCallbacks").asBoolean()
                            && original.path("_events")
                                    .valueStream()
                                    .anyMatch(
                                            event ->
                                                    !event.path("_sent").asBoolean()
                                                            && event.path("_due").asLong() <= now);
            if (!completes && !claims) continue;
            var row = original.deepCopy();
            boolean changed = false;
            if (row.has("_completeAt") && row.path("_completeAt").asLong() <= now) {
                row.put("status", row.path("_finalStatus").asText());
                row.remove("_completeAt");
                if (row.path("status").asText().equals("requires_payment_method"))
                    decline(row, "card_declined", "Payment method declined");
                if (row.path("status").asText().equals("failed"))
                    row.put("failure_reason", "declined");
                enqueueAt(row, eventType(row), now);
                changed = true;
            }
            if (!controls.path("dropCallbacks").asBoolean())
                for (JsonNode candidate : row.withArray("_events")) {
                    var event = (ObjectNode) candidate;
                    if (!event.path("_sent").asBoolean() && event.path("_due").asLong() <= now) {
                        event.put("_due", now + 60000);
                        changed = true;
                        due.add(
                                new Callback(
                                        row.path("id").asText(),
                                        event.path("id").asText(),
                                        publicResource(event),
                                        controls.path("duplicateCallbacks").asInt(0)));
                        if (due.size() >= maximum) break;
                    }
                }
            if (changed) {
                save(row);
                resources.put(row.path("id").asText(), row);
            }
            if (due.size() >= maximum) break;
        }
        if (controls.path("reverseCallbacks").asBoolean()) Collections.reverse(due);
        return due;
    }

    public synchronized void acknowledged(Callback callback, boolean success) {
        ObjectNode original = resources.get(callback.resource());
        if (original == null) return;
        ObjectNode row = original.deepCopy();
        for (JsonNode candidate : row.withArray("_events"))
            if (callback.event().equals(candidate.path("id").asText())) {
                var event = (ObjectNode) candidate;
                int attempts = event.path("_attempts").asInt() + 1;
                event.put("_attempts", attempts)
                        .put("_sent", success)
                        .put(
                                "_due",
                                System.currentTimeMillis()
                                        + Math.min(30000, 100L << Math.min(attempts, 8)));
                save(row);
                resources.put(callback.resource(), row);
                break;
            }
    }

    private long delay() {
        return controls.path("delayMs").asLong(2000);
    }

    private String eventType(ObjectNode row) {
        if (row.path("id").asText().startsWith("re_"))
            return "failed".equals(row.path("status").asText())
                    ? "refund.failed"
                    : "refund.updated";
        return switch (row.path("status").asText()) {
            case "succeeded" -> "payment_intent.succeeded";
            case "requires_payment_method" -> "payment_intent.payment_failed";
            default -> "payment_intent.processing";
        };
    }

    private void enqueue(ObjectNode row, String type, long delay) {
        enqueueAt(row, type, System.currentTimeMillis() + delay);
    }

    private void enqueueAt(ObjectNode row, String type, long dueAt) {
        var events = row.withArray("_events");
        if (events.size() >= 16) throw bad("Fixture event limit reached");
        var event = mapper.createObjectNode();
        event.put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""))
                .put("type", type)
                .put("created", Instant.now().getEpochSecond());
        event.putObject("data").set("object", publicResource(row));
        event.put("_due", dueAt).put("_sent", false);
        events.add(event);
    }

    private JsonNode duplicate(ObjectNode row, Map<String, String> request) {
        if (!row.path("_requestHash").asText().equals(digest(request)))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Idempotency key parameters differ");
        return publicResource(row);
    }

    private static String identity(String prefix, String key) {
        if (key == null || !key.matches("[!-~]{1,255}")) throw bad("Idempotency key required");
        return prefix
                + "_"
                + UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
    }

    private String digest(Map<String, String> data) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(mapper.writeValueAsBytes(new TreeMap<>(data))));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long positive(String value) {
        try {
            long n = Long.parseLong(value);
            if (n <= 0) throw new IllegalArgumentException();
            return n;
        } catch (Exception e) {
            throw bad("Positive amount required");
        }
    }

    private void capacity() {
        if (resources.size() >= 5000)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Fixture ledger full");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ObjectNode publicResource(ObjectNode row) {
        ObjectNode copy = row.deepCopy();
        var fields = new ArrayList<String>();
        copy.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (k.startsWith("_")) fields.add(k);
                        });
        copy.remove(fields);
        return copy;
    }

    private void save(ObjectNode row) {
        write(row.path("id").asText() + ".json", row);
    }

    private void write(String name, ObjectNode value) {
        Path target = directory.resolve(name).normalize();
        if (!target.getParent().equals(directory))
            throw new IllegalStateException("Invalid fixture path");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "fixture-", ".tmp");
            Files.writeString(temporary, mapper.writeValueAsString(value), StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            throw new IllegalStateException("Provider fixture persistence unavailable");
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                }
        }
    }
}

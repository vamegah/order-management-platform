package com.example.omp.adapter.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);

    private record Entry(String requestHash, Object response, Instant createdAt) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public Optional<Object> lookup(String key, String requestHash) {
        if (key == null) return Optional.empty();
        Entry entry = store.get(key);
        if (entry == null) return Optional.empty();
        if (entry.createdAt().plus(TTL).isBefore(Instant.now())) {
            store.remove(key);
            return Optional.empty();
        }
        if (!entry.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency key reused with different payload");
        }
        return Optional.of(entry.response());
    }

    public void store(String key, String requestHash, Object response) {
        if (key == null) return;
        store.put(key, new Entry(requestHash, response, Instant.now()));
    }
}

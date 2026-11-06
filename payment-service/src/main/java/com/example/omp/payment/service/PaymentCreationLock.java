package com.example.omp.payment.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes keyed payment creation across REST/gRPC replicas until transaction completion. */
@Component
public class PaymentCreationLock {
    private final JdbcTemplate jdbc;

    public PaymentCreationLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(String key) {
        if (key == null) return;
        if (key.isBlank() || key.length() > 255 || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid payment idempotency key");
        }
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        // A hash collision only serializes unrelated keys; identity comparisons use the full key.
        jdbc.query("SELECT pg_advisory_xact_lock(1346459981, hashtext(?))", result -> null, key);
    }
}

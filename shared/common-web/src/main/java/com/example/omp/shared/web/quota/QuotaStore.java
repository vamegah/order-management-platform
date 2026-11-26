package com.example.omp.shared.web.quota;

/** A positive result is the server-computed wait in milliseconds; zero grants one token. */
@FunctionalInterface
public interface QuotaStore {
    /** Check connectivity without spending an identity's quota. */
    default void check() {}

    long acquire(String key, int capacity);
}

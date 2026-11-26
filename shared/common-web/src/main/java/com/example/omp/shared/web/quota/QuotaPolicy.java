package com.example.omp.shared.web.quota;

/** Fixed cost classes keep key and metric cardinality independent of request paths. */
public enum QuotaPolicy {
    READ(120),
    WRITE(120),
    GRAPHQL(60),
    EXPENSIVE(30),
    WEBHOOK(6000),
    HANDSHAKE(6000),
    CONNECT(30),
    SUBSCRIBE(60),
    OPERATOR(600),
    OPERATOR_STREAM(6000);
    private final int capacity;

    QuotaPolicy(int capacity) {
        this.capacity = capacity;
    }

    public int capacity(double multiplier) {
        return Math.max(1, (int) Math.floor(capacity * multiplier));
    }
}

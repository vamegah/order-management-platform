package com.example.omp.shared.events.outbox;

/**
 * Lifecycle of an outbox row.
 *
 * <p>Transitions:
 *
 * <pre>
 *   PENDING ──► PUBLISHED    (drainer published successfully)
 *      │
 *      └──────► FAILED       (retry threshold exceeded)
 * </pre>
 *
 * <p>PUBLISHED and FAILED are terminal in normal operation. FAILED rows require operator
 * intervention (inspect, fix, and reset to PENDING via SQL).
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}

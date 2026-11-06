package com.example.omp.inventory.domain;

/**
 * State of a stock reservation.
 *
 * <p>Lifecycle:
 *
 * <pre>
 *   ACTIVE ──► COMMITTED  (terminal — stock decremented)
 *      │
 *      ├───► RELEASED    (terminal — stock returned to available)
 *      │
 *      └───► EXPIRED     (terminal — background job, Phase 7)
 * </pre>
 *
 * <p>Values must match the {@code chk_reservation_status} check constraint in
 * V1__initial_schema.sql.
 */
public enum ReservationStatus {

    /** Stock is held; can still be released or committed. */
    ACTIVE,

    /** Converted to a permanent decrement. Terminal. */
    COMMITTED,

    /** Released back to available stock. Terminal. */
    RELEASED,

    /** Expired without commit or release (system-cleaned). Terminal. */
    EXPIRED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}

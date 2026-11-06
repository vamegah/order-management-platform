package com.example.omp.order.domain;

import java.util.Set;

/**
 * Order lifecycle states.
 *
 * <p>Legal transitions are encoded in {@link #canTransitionTo}. The state machine:
 *
 * <pre>
 *   DRAFT ──► PLACED ──► CONFIRMED ──► FULFILLING ──► SHIPPED ──► DELIVERED
 *              │            │
 *              │            ▼
 *              │         CANCELLED
 *              │
 *              └──────► CANCELLED
 *
 *   (DRAFT can also be CANCELLED directly.)
 * </pre>
 *
 * <p>DELIVERED and CANCELLED are terminal — no further transitions are permitted.
 */
public enum OrderStatus {

    /** Cart-in-progress. Lines can be added or removed. */
    DRAFT,

    /** Placed by the customer. Awaiting payment confirmation. */
    PLACED,

    /** Payment confirmed. Awaiting fulfillment. */
    CONFIRMED,

    /** Shipment requested durably. Inventory commits may be partially complete; no cancellation. */
    FULFILLING,

    /** Shipped. In transit to the customer. */
    SHIPPED,

    /** Delivered. Terminal state. */
    DELIVERED,

    /** Cancelled. Terminal state. */
    CANCELLED;

    /**
     * Legal transitions from this status.
     *
     * <ul>
     *   <li>DRAFT → PLACED (customer checks out)
     *   <li>DRAFT → CANCELLED (customer abandons)
     *   <li>PLACED → CONFIRMED (payment succeeds)
     *   <li>PLACED → CANCELLED (payment fails or admin cancels)
     *   <li>CONFIRMED → FULFILLING (durable shipment intent)
     *   <li>FULFILLING → SHIPPED (all inventory commits acknowledged)
     *   <li>CONFIRMED → CANCELLED (admin cancels before shipping)
     *   <li>SHIPPED → DELIVERED (carrier confirms delivery)
     * </ul>
     */
    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> Set.of(PLACED, CANCELLED);
            case PLACED -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> Set.of(FULFILLING, CANCELLED);
            case FULFILLING -> Set.of(SHIPPED);
            case SHIPPED -> Set.of(DELIVERED);
            case DELIVERED -> Set.of(); // terminal
            case CANCELLED -> Set.of(); // terminal
        };
    }

    /** True if a transition from {@code this} to {@code next} is legal. */
    public boolean canTransitionTo(OrderStatus next) {
        return allowedTransitions().contains(next);
    }

    /** True if this status is terminal (no further transitions possible). */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}

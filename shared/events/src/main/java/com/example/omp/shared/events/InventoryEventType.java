package com.example.omp.shared.events;

public final class InventoryEventType {

    private InventoryEventType() {}

    public static final String RESERVATION_CREATED = "RESERVATION_CREATED";
    public static final String RESERVATION_COMMITTED = "RESERVATION_COMMITTED";
    public static final String RESERVATION_RELEASED = "RESERVATION_RELEASED";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
}

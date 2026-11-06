package com.example.omp.order.error;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    private final String identifier;

    public OrderNotFoundException(UUID id) {
        super("Order not found: " + id);
        this.identifier = id.toString();
    }

    public OrderNotFoundException(String orderNumber) {
        super("Order not found with number: " + orderNumber);
        this.identifier = orderNumber;
    }

    public String getIdentifier() {
        return identifier;
    }
}

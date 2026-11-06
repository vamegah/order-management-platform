package com.example.omp.order.service;

import com.example.omp.order.domain.OrderNumber;
import com.example.omp.order.repository.OrderRepository;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Authorizes public order access using the verified customer claim and immutable ownership. */
@Service
public class OrderAuthorization {
    private final OrderRepository orders;

    public OrderAuthorization(OrderRepository orders) {
        this.orders = orders;
    }

    public boolean ownsCustomer(UUID requested, Jwt token) {
        return requested != null && requested.equals(customerId(token));
    }

    public boolean ownsOrder(UUID id, Jwt token) {
        UUID customer = customerId(token);
        return customer != null && id != null && orders.existsByIdAndCustomerId(id, customer);
    }

    public boolean ownsOrderNumber(String number, Jwt token) {
        UUID customer = customerId(token);
        if (customer == null) return false;
        try {
            return orders.existsByOrderNumberAndCustomerId(OrderNumber.of(number), customer);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
    }

    private UUID customerId(Jwt token) {
        if (token == null) return null;
        try {
            Object claim = token.getClaims().get("customer_id");
            if (!(claim instanceof String raw)) return null;
            UUID id = UUID.fromString(raw);
            return id.toString().equalsIgnoreCase(raw) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}

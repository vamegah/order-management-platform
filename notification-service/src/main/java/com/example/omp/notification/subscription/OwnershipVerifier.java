package com.example.omp.notification.subscription;

import com.example.omp.notification.clients.CustomerClient;
import com.example.omp.notification.clients.OrderClient;
import com.example.omp.notification.clients.PaymentClient;
import com.example.omp.notification.websocket.JwtPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Checks ownership with the caller's relayed JWT; lookup failures never grant access. */
@Component
public class OwnershipVerifier {
    private final CustomerClient customers;
    private final OrderClient orders;
    private final PaymentClient payments;

    public OwnershipVerifier(CustomerClient customers, OrderClient orders, PaymentClient payments) {
        this.customers = customers;
        this.orders = orders;
        this.payments = payments;
    }

    public void verifyOrderOwnership(String id, JwtPrincipal principal) {
        if (principal.isAdmin()) return;
        UUID customer = resolveCustomerId(principal);
        if (!customer.equals(orders.getOrder(UUID.fromString(id)).customerId())) deny();
    }

    public void verifyPaymentOwnership(String id, JwtPrincipal principal) {
        if (principal.isAdmin()) return;
        UUID customer = resolveCustomerId(principal);
        if (!customer.equals(payments.getPayment(UUID.fromString(id)).customerId())) deny();
    }

    public UUID resolveCustomerId(JwtPrincipal principal) {
        if (principal.customerId() != null) return principal.customerId();
        var customer = customers.me();
        if (customer == null || customer.id() == null) deny();
        return customer.id();
    }

    private static void deny() {
        throw new SubscriptionUnauthorizedException("Resource subscription is not authorized");
    }
}

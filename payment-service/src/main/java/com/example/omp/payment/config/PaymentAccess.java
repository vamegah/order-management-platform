package com.example.omp.payment.config;

import com.example.omp.payment.repository.PaymentRepository;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("paymentAccess")
public class PaymentAccess {
    private final PaymentRepository payments;

    public PaymentAccess(PaymentRepository payments) {
        this.payments = payments;
    }

    public boolean canAccess(UUID id, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !authentication.isAuthenticated()) return false;
        if (isAdministrator(authentication)) return true;
        String customer = token.getToken().getClaimAsString("customer_id");
        if (customer == null) return false;
        return payments.findById(id)
                .map(payment -> payment.getCustomerId().toString().equals(customer))
                .orElse(false);
    }

    public boolean isAdministrator(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !authentication.isAuthenticated()) return false;
        if (authentication.getAuthorities().stream()
                .anyMatch(role -> role.getAuthority().equalsIgnoreCase("ROLE_admin"))) return true;
        Map<String, Object> realm = token.getToken().getClaimAsMap("realm_access");
        if (realm != null
                && realm.get("roles") instanceof Collection<?> roles
                && roles.stream().anyMatch(role -> "admin".equalsIgnoreCase(role.toString())))
            return true;
        return false;
    }

    public boolean canRefund(UUID id, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken)
                || !authentication.isAuthenticated()) return false;
        if (authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_order.reconcile")))
            return true;
        return canAccess(id, authentication);
    }
}

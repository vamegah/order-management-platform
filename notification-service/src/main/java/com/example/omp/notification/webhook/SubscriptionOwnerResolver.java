package com.example.omp.notification.webhook;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Derives (ownerType, ownerId) from a validated JWT.
 *
 * <p>Conventions: - realm_access.roles contains "ADMIN" -> ADMIN, sub - claim "customer_id" present
 * -> CUSTOMER, customer_id - otherwise -> CUSTOMER, sub
 */
@Component
public class SubscriptionOwnerResolver {

    public SubscriptionOwner resolve(Jwt jwt) {
        if (hasRole(jwt, "ADMIN")) {
            return new SubscriptionOwner(SubscriptionOwnerType.ADMIN, uuid(jwt.getSubject()));
        }
        String customerId = jwt.getClaimAsString("customer_id");
        if (customerId != null && !customerId.isBlank()) {
            return new SubscriptionOwner(SubscriptionOwnerType.CUSTOMER, uuid(customerId));
        }
        return new SubscriptionOwner(SubscriptionOwnerType.CUSTOMER, uuid(jwt.getSubject()));
    }

    private boolean hasRole(Jwt jwt, String role) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof java.util.Map<?, ?> map) {
            Object roles = map.get("roles");
            if (roles instanceof java.util.Collection<?> coll) {
                return coll.stream().anyMatch(r -> role.equalsIgnoreCase(String.valueOf(r)));
            }
        }
        return false;
    }

    private UUID uuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("JWT subject/customer_id is null");
        }
        return UUID.fromString(value);
    }
}

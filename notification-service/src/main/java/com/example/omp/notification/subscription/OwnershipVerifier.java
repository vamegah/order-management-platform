package com.example.omp.notification.subscription;

import com.example.omp.notification.clients.CustomerClient;
import com.example.omp.notification.clients.OrderClient;
import com.example.omp.notification.clients.PaymentClient;
import com.example.omp.notification.websocket.JwtPrincipal;
import com.example.omp.notification.websocket.JwtThreadLocalHolder;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Bounded positive proofs, scoped to the exact signed token/caller/resource; failures never grant
 * access.
 */
@Component
public class OwnershipVerifier {
    private static final int MAX_PROOFS = 4096, MAX_LOOKUPS = 64;
    private static final long PROOF_NANOS = TimeUnit.SECONDS.toNanos(30);

    private record Key(
            String kind, UUID resource, String subject, UUID customer, String tokenHash) {}

    private final Map<Key, Long> proofs = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, CompletableFuture<Void>> lookups = new HashMap<>();
    private long generation;
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
        UUID resource = UUID.fromString(id), customer = resolveCustomerId(principal);
        verify(
                "order",
                resource,
                customer,
                principal,
                () -> {
                    var result = orders.getOrder(resource);
                    return result != null
                            && resource.equals(result.id())
                            && customer.equals(result.customerId());
                });
    }

    public void verifyPaymentOwnership(String id, JwtPrincipal principal) {
        if (principal.isAdmin()) return;
        UUID resource = UUID.fromString(id), customer = resolveCustomerId(principal);
        verify(
                "payment",
                resource,
                customer,
                principal,
                () -> {
                    var result = payments.getPayment(resource);
                    return result != null
                            && resource.equals(result.id())
                            && customer.equals(result.customerId());
                });
    }

    private void verify(
            String kind,
            UUID resource,
            UUID customer,
            JwtPrincipal principal,
            Supplier<Boolean> lookup) {
        String token = JwtThreadLocalHolder.get();
        // A caller without a relayed token cannot reuse another session's proof.
        if (token == null) {
            if (!lookup.get()) deny();
            return;
        }
        Key key = new Key(kind, resource, principal.sub(), customer, fingerprint(token));
        CompletableFuture<Void> future;
        boolean leader;
        long epoch;
        synchronized (proofs) {
            Long verified = proofs.get(key);
            if (verified != null && System.nanoTime() - verified < PROOF_NANOS) return;
            proofs.remove(key);
            future = lookups.get(key);
            leader = future == null;
            epoch = generation;
            if (leader) {
                if (lookups.size() >= MAX_LOOKUPS)
                    throw new IllegalStateException("Ownership verification capacity reached");
                future = new CompletableFuture<>();
                lookups.put(key, future);
            }
        }
        if (leader) {
            try {
                if (!lookup.get()) deny();
                synchronized (proofs) {
                    if (epoch == generation) {
                        if (proofs.size() >= MAX_PROOFS)
                            proofs.remove(proofs.keySet().iterator().next());
                        proofs.put(key, System.nanoTime());
                    }
                }
                future.complete(null);
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
                throw failure;
            } finally {
                synchronized (proofs) {
                    lookups.remove(key);
                }
            }
        } else {
            try {
                future.get(6, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ownership verification interrupted");
            } catch (TimeoutException timeout) {
                throw new IllegalStateException("Ownership verification timed out");
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException("Ownership verification failed");
            }
        }
    }

    public void invalidate(com.example.omp.shared.events.BaseEvent event) {
        String kind;
        String id;
        if (event instanceof com.example.omp.shared.events.OrderEvent order) {
            kind = "order";
            id = order.orderId();
        } else if (event instanceof com.example.omp.shared.events.PaymentEvent payment) {
            kind = "payment";
            id = payment.paymentId();
        } else return;
        UUID resource;
        try {
            resource = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return;
        }
        synchronized (proofs) {
            generation++;
            proofs.keySet()
                    .removeIf(key -> key.kind().equals(kind) && key.resource().equals(resource));
        }
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            token.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

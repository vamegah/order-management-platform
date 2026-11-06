package com.example.omp.notification.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.notification.websocket.JwtPrincipal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionRegistryTest {

    private SubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SubscriptionRegistry();
    }

    @Test
    void registerAddsSubscription() {
        Subscription sub = makeSub("s1", TopicType.ORDERS, "order-1");

        registry.register(sub);

        assertThat(registry.find(TopicType.ORDERS, "order-1")).containsExactly(sub);
        assertThat(registry.totalSubscriptions()).isEqualTo(1);
        assertThat(registry.totalSessions()).isEqualTo(1);
    }

    @Test
    void removeSessionCleansUpAllIndexes() {
        Subscription sub1 = makeSub("s1", TopicType.ORDERS, "order-1");
        Subscription sub2 = makeSub("s1", TopicType.PAYMENTS, "payment-1");
        registry.register(sub1);
        registry.register(sub2);

        registry.removeSession("s1");

        assertThat(registry.find(TopicType.ORDERS, "order-1")).isEmpty();
        assertThat(registry.find(TopicType.PAYMENTS, "payment-1")).isEmpty();
        assertThat(registry.totalSubscriptions()).isZero();
        assertThat(registry.totalSessions()).isZero();
    }

    @Test
    void findReturnsEmptyForUnknownResource() {
        assertThat(registry.find(TopicType.ORDERS, "unknown")).isEmpty();
    }

    @Test
    void multipleSessionsCanSubscribeToSameDestination() {
        Subscription s1 = makeSub("session-1", TopicType.ORDERS, "order-1");
        Subscription s2 = makeSub("session-2", TopicType.ORDERS, "order-1");
        registry.register(s1);
        registry.register(s2);

        assertThat(registry.find(TopicType.ORDERS, "order-1")).containsExactlyInAnyOrder(s1, s2);
        assertThat(registry.totalSubscriptions()).isEqualTo(2);
        assertThat(registry.totalSessions()).isEqualTo(2);
    }

    @Test
    void unregisterRemovesFromAllIndexes() {
        Subscription sub = makeSub("s1", TopicType.ORDERS, "order-1");
        registry.register(sub);
        registry.unregister(sub);

        assertThat(registry.find(TopicType.ORDERS, "order-1")).isEmpty();
    }

    private Subscription makeSub(String sessionId, TopicType type, String resourceId) {
        JwtPrincipal principal =
                new JwtPrincipal("user-1", "alice", "alice@omp.local", Set.of("customer"));
        String dest =
                switch (type) {
                    case ORDERS -> "/topic/orders/" + resourceId;
                    case INVENTORY -> "/topic/inventory/" + resourceId;
                    case PAYMENTS -> "/topic/payments/" + resourceId;
                    case NOTIFICATIONS -> "/user/queue/notifications";
                };
        return Subscription.create(sessionId, dest, type, resourceId, principal);
    }
}

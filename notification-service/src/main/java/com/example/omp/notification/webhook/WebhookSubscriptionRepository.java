package com.example.omp.notification.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
            SubscriptionOwnerType ownerType, UUID ownerId);

    /**
     * Find all enabled subscriptions whose event_types array contains the given event type. Uses
     * the GIN index on event_types.
     */
    @Query(
            value =
                    """
            SELECT * FROM notifications.webhook_subscription
            WHERE enabled = true
              AND event_types @> ARRAY[:eventType]::text[]
            """,
            nativeQuery = true)
    List<WebhookSubscription> findEnabledByEventType(@Param("eventType") String eventType);

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WebhookSubscription s where s.id=:id")
    java.util.Optional<WebhookSubscription> findLockedById(@Param("id") UUID id);

    long countByEnabledTrue();

    long countByEnabledFalse();
}

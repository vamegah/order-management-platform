package com.example.omp.notification.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookDeliveryAttemptRepository
        extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    List<WebhookDeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    Page<WebhookDeliveryAttempt> findBySubscriptionIdOrderByScheduledAtDesc(
            UUID subscriptionId, Pageable pageable);

    @Query(
            """
            SELECT a FROM WebhookDeliveryAttempt a
            WHERE a.deliveryId = :deliveryId AND a.status = 'SUCCESS'
            """)
    List<WebhookDeliveryAttempt> findSuccessfulAttempts(@Param("deliveryId") UUID deliveryId);

    boolean existsByDeliveryIdAndStatus(UUID deliveryId, DeliveryStatus status);
}

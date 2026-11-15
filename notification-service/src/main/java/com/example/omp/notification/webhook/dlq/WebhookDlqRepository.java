package com.example.omp.notification.webhook.dlq;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookDlqRepository extends JpaRepository<WebhookDlqEntry, UUID> {

    Optional<WebhookDlqEntry> findByDeliveryId(UUID deliveryId);

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "SELECT entry FROM WebhookDlqEntry entry WHERE entry.deliveryId=:deliveryId")
    Optional<WebhookDlqEntry> findLockedByDeliveryId(
            @org.springframework.data.repository.query.Param("deliveryId") UUID deliveryId);

    Page<WebhookDlqEntry> findAllByOrderByReceivedAtDesc(Pageable pageable);

    Page<WebhookDlqEntry> findByReplayedAtIsNullOrderByReceivedAtDesc(Pageable pageable);
}

package com.example.omp.payment.repository;

import com.example.omp.payment.domain.PaymentProviderEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, UUID> {
    @Modifying
    @Query(
            value =
                    """
        INSERT INTO payments.provider_event(id, provider, provider_event_id, event_type, payload, status, created_at)
        VALUES (:id, :provider, :eventId, :eventType, :payload, 'RECEIVED', now())
        ON CONFLICT (provider, provider_event_id) DO NOTHING
        """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("payload") String payload);
}

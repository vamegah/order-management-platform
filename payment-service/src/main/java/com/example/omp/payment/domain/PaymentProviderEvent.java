package com.example.omp.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "provider_event",
        schema = "payments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_event_id"}))
public class PaymentProviderEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private String status = "RECEIVED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public void setProvider(String value) {
        provider = value;
    }

    public void setProviderEventId(String value) {
        providerEventId = value;
    }

    public void setEventType(String value) {
        eventType = value;
    }

    public void setPayload(String value) {
        payload = value;
    }

    public void markIgnored() {
        status = "IGNORED";
    }

    public void markProcessed() {
        status = "PROCESSED";
    }

    public void markFailed(String reason) {
        status = "FAILED";
    }
}

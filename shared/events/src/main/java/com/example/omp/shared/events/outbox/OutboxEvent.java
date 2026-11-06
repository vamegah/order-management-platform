package com.example.omp.shared.events.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row in the transactional outbox table.
 *
 * <p>Inserted in the same database transaction as the state change that produced the event. Drained
 * to Kafka by {@link OutboxDrainer}.
 *
 * <p><b>Schema-agnostic.</b> This entity has no {@code @Table(schema=...)} annotation. It lives in
 * whichever schema the owning service uses as its default (via {@code currentSchema=...} in the
 * JDBC URL). The Flyway migration for each service creates the table in that schema.
 *
 * <p><b>Payload as JSON.</b> The {@code payload} column is {@code jsonb} in Postgres. Hibernate
 * maps it via {@link JdbcTypeCode} with {@link SqlTypes#JSON} so that a Java {@code String}
 * containing JSON is stored without being re-escaped.
 *
 * <p><b>Immutability.</b> Most fields are set at construction and never modified. {@code status},
 * {@code publishedAt}, {@code attempts}, and {@code lastError} are updated by the drainer via
 * domain methods ({@link #markPublished()}, {@link #recordAttemptFailure(String, int)}).
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
        // JPA only.
    }

    public OutboxEvent(
            String aggregateId,
            String aggregateType,
            String eventType,
            String topic,
            String payload) {
        this.aggregateId = required(aggregateId, 255, "aggregateId");
        this.aggregateType = required(aggregateType, 50, "aggregateType");
        this.eventType = required(eventType, 100, "eventType");
        this.topic = required(topic, 255, "topic");
        this.payload = required(payload, Integer.MAX_VALUE, "payload");
        this.status = OutboxStatus.PENDING;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /** Marks the row as published. Called by the drainer after a successful Kafka send. */
    public void markPublished() {
        if (status == OutboxStatus.PUBLISHED) return;
        if (status != OutboxStatus.PENDING)
            throw new IllegalStateException("Only pending events can be published");
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records a failed publish attempt. If attempts have reached the supplied threshold, the row
     * transitions to FAILED; otherwise it stays PENDING and will be retried on the next poll.
     *
     * @param errorMessage the exception message (truncated to 1000 chars for storage sanity)
     * @param retryThreshold after this many attempts, transition to FAILED
     */
    public void recordAttemptFailure(String errorMessage, int retryThreshold) {
        if (retryThreshold < 1)
            throw new IllegalArgumentException("Retry threshold must be positive");
        if (status != OutboxStatus.PENDING)
            throw new IllegalStateException("Only pending events can fail an attempt");
        this.attempts++;
        if (errorMessage != null && errorMessage.length() > 1000) {
            this.lastError = errorMessage.substring(0, 1000);
        } else {
            this.lastError = errorMessage;
        }
        if (this.attempts >= retryThreshold) {
            this.status = OutboxStatus.FAILED;
        }
    }

    private static String required(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and fit its storage column");
        }
        return value;
    }

    // -------------------------------------------------------------------------
    // Getters (no setters — status changes go through the domain methods)
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "OutboxEvent{id="
                + id
                + ", eventType='"
                + eventType
                + "', status="
                + status
                + ", attempts="
                + attempts
                + "}";
    }
}
